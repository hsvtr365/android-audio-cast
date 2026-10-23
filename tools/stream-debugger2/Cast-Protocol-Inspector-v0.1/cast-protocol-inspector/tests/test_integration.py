"""Local-loopback integration: starts receiver, sends framed Cast V2 packets over TLS."""
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import ssl
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


class IntegrationTest(unittest.TestCase):
    def test_local_receiver(self):
        import socket
        def free_port():
            with socket.socket() as s:
                s.bind(('127.0.0.1', 0))
                return s.getsockname()[1]
        cast_port, ui_port = free_port(), free_port()
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ, PYTHONPATH=str(ROOT))
            receiver = subprocess.Popen([sys.executable, '-m', 'cast_inspector',
                '--no-mdns', '--listen', '127.0.0.1', '--cast-port', str(cast_port),
                '--ui-port', str(ui_port), '--log-dir', tmp], cwd=ROOT,
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, env=env)
            try:
                status = None
                for _ in range(80):
                    if receiver.poll() is not None:
                        raise RuntimeError('receiver exited unexpectedly: ' + receiver.stderr.read().decode())
                    try:
                        with urlopen(f'http://127.0.0.1:{ui_port}/api/status', timeout=.3) as response:
                            status = json.load(response)
                        break
                    except Exception:
                        time.sleep(.1)
                self.assertIsNotNone(status, 'dashboard did not start')
                self.assertEqual(status['mdns'], 'disabled')
                async def challenge():
                    from cast_inspector.protocol import CastMessage, pack_frame, field_bytes, decode_fields
                    from cast_inspector.server import NS_AUTH
                    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                    ctx.check_hostname = False
                    ctx.verify_mode = ssl.CERT_NONE  # Local mock receiver only.
                    reader, writer = await asyncio.open_connection('127.0.0.1', cast_port, ssl=ctx)
                    writer.write(pack_frame(CastMessage('test-auth', 'receiver-0', NS_AUTH,
                        payload_binary=field_bytes(1, field_bytes(2, b'test-nonce')))))
                    await writer.drain()
                    size = int.from_bytes(await reader.readexactly(4), 'big')
                    reply = CastMessage.decode(await reader.readexactly(size))
                    self.assertIn(3, decode_fields(reply.payload_binary))
                    writer.close()
                    await writer.wait_closed()
                asyncio.run(asyncio.wait_for(challenge(), 5))
                result = subprocess.run([sys.executable, str(ROOT / 'scripts/demo_sender.py'),
                    '--host', '127.0.0.1', '--port', str(cast_port)], cwd=ROOT,
                    env=env, capture_output=True, text=True, timeout=12)
                self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
                self.assertIn('PASS:', result.stdout)
                with urlopen(f'http://127.0.0.1:{ui_port}/api/events', timeout=2) as response:
                    events = json.load(response)['events']
                self.assertTrue(any(e['category'] == 'PONG' and e['direction'] == 'SEND' for e in events))
                self.assertTrue(any(e['category'] == 'LOAD' and e['direction'] == 'RECEIVE' for e in events))
                self.assertTrue(any(e['category'] == 'TLS_CONNECTED' for e in events))
                self.assertTrue(any(e['category'] == 'TCP_CONNECTED' for e in events))
                self.assertTrue(any(e['category'] == 'AUTH_BLOCKED' for e in events))
                self.assertTrue(any(e['category'] == 'AUTH_ERROR' for e in events))
                with urlopen(f'http://127.0.0.1:{ui_port}/api/status', timeout=2) as response:
                    status = json.load(response)
                self.assertFalse(status['device_auth_supported'])
                self.assertTrue(any(item['phase'] == 'AUTH_BLOCKED' for item in status['history']))
                self.assertTrue((Path(tmp) / 'cast-events.jsonl').exists())
            finally:
                receiver.terminate()
                try:
                    receiver.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    receiver.kill()
                    receiver.wait()
                receiver.stderr.close()


if __name__ == '__main__':
    unittest.main()
