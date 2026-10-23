"""Cast V2 debug receiver, event collection and loopback-only HTTP/WebSocket UI."""
from __future__ import annotations

import argparse
import asyncio
import collections
import contextlib
import json
from pathlib import Path
import re
import webbrowser
import signal
import ssl
import struct
import uuid
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlsplit, urlunsplit

from aiohttp import web, WSMsgType

from .certificates import ensure_local_cert
from .discovery import Advertiser, make_device_id, select_ip
from .protocol import (CastMessage, MAX_FRAME_BYTES, ProtocolError, pack_frame,
                       decode_fields, field_bytes, field_varint)

NS_BASE = 'urn:x-cast:com.google.cast.'
NS_CONNECTION = NS_BASE + 'tp.connection'
NS_HEARTBEAT = NS_BASE + 'tp.heartbeat'
NS_AUTH = NS_BASE + 'tp.deviceauth'
NS_RECEIVER = NS_BASE + 'receiver'
NS_MEDIA = NS_BASE + 'media'

PRIVATE_FIELD = re.compile(r'password|token|authorization|cookie|secret|api.?key|credential|certificate|signature', re.I)


def redact(value: Any, depth: int = 0) -> Any:
    """Avoid rendering obvious credentials; binary authentication payloads are never decoded to UI."""
    if depth > 10:
        return '[MAX DEPTH]'
    if isinstance(value, dict):
        return {str(k): '[REDACTED]' if PRIVATE_FIELD.search(str(k)) else redact(v, depth + 1)
                for k, v in list(value.items())[:100]}
    if isinstance(value, list):
        return [redact(v, depth + 1) for v in value[:100]]
    if isinstance(value, str):
        if value.startswith(('http://', 'https://')):
            try:
                parts = urlsplit(value)
                host = parts.hostname or ''
                if parts.port:
                    host += f':{parts.port}'
                path = re.sub(r'(?<=/)[a-fA-F0-9]{32}(?=/|$)', '[REDACTED]', parts.path)
                value = urlunsplit((parts.scheme, host, path, '[REDACTED]' if parts.query else '', ''))
            except ValueError:
                value = '[INVALID URL]'
        return value[:4000] + (' …[TRUNCATED]' if len(value) > 4000 else '')
    if isinstance(value, (bool, int, float)) or value is None:
        return value
    return str(type(value).__name__)


class EventBus:
    def __init__(self, path: Path, raw: bool = False):
        self.events = collections.deque(maxlen=2000)
        self.sockets: set[web.WebSocketResponse] = set()
        self.path = path
        self.raw = raw
        self.counts = {'RECEIVE': 0, 'SEND': 0, 'SYSTEM': 0, 'ERROR': 0}
        self.total = 0
        path.parent.mkdir(parents=True, exist_ok=True)

    def emit(self, direction: str, category: str, summary: str,
             *, conn: str = '', peer: str = '', namespace: str = '',
             source: str = '', target: str = '', payload: Any = None,
             raw_bytes: bytes | None = None):
        self.total += 1
        self.counts[direction] = self.counts.get(direction, 0) + 1
        event = {
            'id': self.total, 'ts': datetime.now(timezone.utc).isoformat(timespec='milliseconds'),
            'direction': direction, 'category': category, 'summary': summary[:220],
            'connection': conn, 'peer': peer, 'namespace': namespace,
            'source': source, 'target': target, 'payload': redact(payload),
        }
        # Raw frame recording is intentionally opt-in; it can expose credentials.
        if self.raw and raw_bytes is not None:
            event['raw_hex'] = raw_bytes.hex()
        self.events.append(event)
        with self.path.open('a', encoding='utf-8') as fp:
            fp.write(json.dumps(event, ensure_ascii=False, separators=(',', ':')) + '\n')
        for ws in tuple(self.sockets):
            try:
                asyncio.create_task(self._send(ws, event))
            except RuntimeError:
                pass
        label = f'{direction:7} {category:18} {summary[:85]}'
        print(f'[{event["ts"][11:23]}] {label}', flush=True)
        return event

    async def _send(self, ws: web.WebSocketResponse, event: dict):
        try:
            if not ws.closed:
                await ws.send_json({'kind': 'event', 'event': event})
        except Exception:
            self.sockets.discard(ws)


class FakeReceiverState:
    """Protocol-only responses; no media is played and no app is actually launched."""
    def __init__(self):
        self.app_id = None
        self.session_id = None
        self.transport_id = None
        self.volume = 0.5
        self.muted = False
        self.media_session_id = 1
        self.media = None
        self.player_state = 'IDLE'
        self.position = 0.0

    def receiver_status(self, request_id: Any = None) -> dict:
        status: dict[str, Any] = {
            'volume': {'level': self.volume, 'muted': self.muted},
            'isActiveInput': True, 'isStandBy': False, 'applications': []
        }
        if self.app_id:
            status['applications'] = [{
                'appId': self.app_id, 'displayName': 'Cast Debug (mock)',
                'sessionId': self.session_id, 'transportId': self.transport_id,
                'isIdleScreen': False, 'launchedFromCloud': False,
                'namespaces': [{'name': NS_MEDIA}]
            }]
        packet = {'type': 'RECEIVER_STATUS', 'status': status}
        if request_id is not None:
            packet['requestId'] = request_id
        return packet

    def media_status(self, request_id: Any = None) -> dict:
        state: dict[str, Any] = {
            'mediaSessionId': self.media_session_id,
            'playbackRate': 1, 'playerState': self.player_state,
            'currentTime': self.position, 'supportedMediaCommands': 0,
            'volume': {'level': self.volume, 'muted': self.muted},
        }
        if self.media is not None:
            state['media'] = self.media
        result = {'type': 'MEDIA_STATUS', 'status': [state]}
        if request_id is not None:
            result['requestId'] = request_id
        return result


class CastInspector:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.ip = select_ip(args.ip)
        id_file = Path(args.log_dir) / 'device-id.txt'
        id_file.parent.mkdir(parents=True, exist_ok=True)
        if id_file.exists() and len(id_file.read_text(encoding='ascii').strip()) == 32:
            self.device_id = id_file.read_text(encoding='ascii').strip()
        else:
            self.device_id = make_device_id()
            id_file.write_text(self.device_id, encoding='ascii')
        self.bus = EventBus(Path(args.log_dir) / 'cast-events.jsonl', args.include_raw)
        self.fake = FakeReceiverState()
        self.connections: dict[str, dict] = {}
        self.history = collections.deque(maxlen=50)
        self.tls_context = None
        self.advertiser = None
        self.mdns_status = 'disabled' if args.no_mdns else 'starting'
        self.cast_server = None
        self.ui_runner = None
        self.closing = asyncio.Event()
        self.ui_app = web.Application(client_max_size=1024 * 1024)
        self.ui_app.router.add_get('/', self.home)
        self.ui_app.router.add_get('/api/status', self.status)
        self.ui_app.router.add_get('/api/events', self.events)
        self.ui_app.router.add_get('/api/export', self.export)
        self.ui_app.router.add_get('/ws', self.websocket)
        self.ui_app.router.add_static('/static/', str(Path(__file__).parent / 'static'))

    async def home(self, request):
        return web.FileResponse(Path(__file__).parent / 'static' / 'index.html')

    async def status(self, request):
        return web.json_response({'name': self.args.name, 'lan_ip': self.ip,
                                  'cast_port': self.args.cast_port, 'ui_port': self.args.ui_port,
                                  'mdns': self.mdns_status, 'connections': list(self.connections.values()),
                                  'counts': self.bus.counts, 'total': self.bus.total,
                                  'log_file': str(self.bus.path), 'raw_capture': self.args.include_raw,
                                  'mock_app': self.fake.app_id, 'history': list(self.history),
                                  'device_auth_supported': False, 'media_playback': False})

    async def events(self, request):
        return web.json_response({'events': list(self.bus.events)})

    async def export(self, request):
        # Exports in-memory bounded events. Full disk log remains in log_dir.
        text = ''.join(json.dumps(item, ensure_ascii=False) + '\n' for item in self.bus.events)
        return web.Response(text=text, content_type='application/x-ndjson',
                            headers={'Content-Disposition': 'attachment; filename="cast-capture.jsonl"',
                                     'X-Content-Type-Options': 'nosniff'})

    async def websocket(self, request):
        origin = request.headers.get('Origin', '')
        if origin and origin not in (f'http://127.0.0.1:{self.args.ui_port}',
                                     f'http://localhost:{self.args.ui_port}'):
            raise web.HTTPForbidden(text='local UI only')
        ws = web.WebSocketResponse(heartbeat=20, max_msg_size=512)
        await ws.prepare(request)
        self.bus.sockets.add(ws)
        try:
            async for message in ws:
                if message.type == WSMsgType.ERROR:
                    break
        finally:
            self.bus.sockets.discard(ws)
        return ws

    async def run(self):
        cert, key = ensure_local_cert(Path(self.args.log_dir) / 'certs')
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(str(cert), str(key))
        self.tls_context = ctx
        self.cast_server = await asyncio.start_server(self.accept_client,
                                                     self.args.listen, self.args.cast_port)
        self.ui_runner = web.AppRunner(self.ui_app, access_log=None)
        await self.ui_runner.setup()
        for offset in range(10):
            ui_site = web.TCPSite(self.ui_runner, '127.0.0.1', self.args.ui_port)
            try:
                await ui_site.start()
                break
            except OSError as error:
                if error.errno not in (48, 98, 10048) or offset == 9:
                    self.cast_server.close()
                    await self.cast_server.wait_closed()
                    await self.ui_runner.cleanup()
                    raise
                self.args.ui_port += 1
        self.bus.emit('SYSTEM', 'SERVER', f'TLS listening on {self.args.listen}:{self.args.cast_port}')
        self.bus.emit('SYSTEM', 'DASHBOARD', f'http://127.0.0.1:{self.args.ui_port}')
        if not self.args.no_mdns:
            self.advertiser = Advertiser(self.args.name, self.ip, self.args.cast_port, self.device_id)
            try:
                await asyncio.to_thread(self.advertiser.start)
                self.mdns_status = 'advertising'
                self.bus.emit('SYSTEM', 'MDNS', f'{self.args.name} @ {self.ip}:{self.args.cast_port}')
            except Exception as e:
                self.mdns_status = 'error'
                self.bus.emit('ERROR', 'MDNS', f'{type(e).__name__}: {e}')
        if self.ip.startswith('127.'):
            self.bus.emit('ERROR', 'NETWORK', 'No LAN IP found. Use --ip <LAN-IP> to advertise on Wi-Fi/Ethernet.')
        print(f'\nDASHBOARD: http://127.0.0.1:{self.args.ui_port}\n', flush=True)
        if self.args.open_browser:
            webbrowser.open(f'http://127.0.0.1:{self.args.ui_port}', new=2)
        try:
            await self.closing.wait()
        finally:
            self.bus.emit('SYSTEM', 'SERVER', 'Shutdown requested')
            self.cast_server.close()
            await self.cast_server.wait_closed()
            for ws in tuple(self.bus.sockets):
                await ws.close()
            await self.ui_runner.cleanup()
            if self.advertiser:
                await asyncio.to_thread(self.advertiser.stop)

    async def accept_client(self, reader, writer):
        conn = uuid.uuid4().hex[:8]
        peer_name = writer.get_extra_info('peername')
        peer = str(peer_name[0]) + ':' + str(peer_name[1]) if peer_name else 'unknown'
        self.connections[conn] = {'id': conn, 'peer': peer, 'phase': 'TCP',
                                  'connected_at': datetime.now(timezone.utc).isoformat()}
        self.bus.emit('SYSTEM', 'TCP_CONNECTED', 'TCP accepted; negotiating TLS', conn=conn, peer=peer)
        try:
            await writer.start_tls(self.tls_context, ssl_handshake_timeout=8)
        except (OSError, ConnectionError, asyncio.TimeoutError) as error:
            self.connections[conn]['phase'] = 'TLS_FAILED'
            self.bus.emit('ERROR', 'TLS_FAILED', type(error).__name__ + ': TLS handshake failed', conn=conn, peer=peer)
            self.history.append(dict(self.connections.pop(conn)))
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()
            return
        await self.handle_client(reader, writer, conn)

    async def handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, conn=None):
        conn = conn or uuid.uuid4().hex[:8]
        peer_name = writer.get_extra_info('peername')
        peer = str(peer_name[0]) + ':' + str(peer_name[1]) if peer_name else 'unknown'
        info = {'id': conn, 'peer': peer, 'phase': 'TLS', 'connected_at': datetime.now(timezone.utc).isoformat()}
        self.connections[conn] = info
        self.bus.emit('SYSTEM', 'TLS_CONNECTED', 'TLS handshake complete', conn=conn, peer=peer)
        try:
            while True:
                head = await asyncio.wait_for(reader.readexactly(4), timeout=40)
                size = struct.unpack('>I', head)[0]
                if size == 0 or size > MAX_FRAME_BYTES:
                    raise ProtocolError(f'invalid frame size {size}')
                wire = await asyncio.wait_for(reader.readexactly(size), timeout=10)
                msg = CastMessage.decode(wire)
                parsed: Any = None
                msg_type = 'BINARY'
                if msg.payload_utf8 is not None:
                    try:
                        parsed = json.loads(msg.payload_utf8)
                        if not isinstance(parsed, dict):
                            parsed = {'value': parsed}
                        msg_type = str(parsed.get('type') or 'JSON')[:100]
                    except json.JSONDecodeError:
                        msg_type = 'TEXT'
                        parsed = {'text': msg.payload_utf8[:4000]}
                else:
                    parsed = {'bytes': len(msg.payload_binary or b''),
                              'note': 'binary payload hidden (auth material possible)'}
                self.bus.emit('RECEIVE', msg_type, f'{msg.source_id} → {msg.destination_id}',
                              conn=conn, peer=peer, namespace=msg.namespace,
                              source=msg.source_id, target=msg.destination_id,
                              payload=parsed, raw_bytes=wire)
                await self.dispatch(msg, parsed, writer, conn, peer)
        except asyncio.TimeoutError:
            self.bus.emit('ERROR', 'TIMEOUT', 'No complete frame received within deadline', conn=conn, peer=peer)
        except asyncio.IncompleteReadError:
            pass
        except (ProtocolError, ConnectionError, OSError, ssl.SSLError) as e:
            self.bus.emit('ERROR', 'PROTOCOL', str(e), conn=conn, peer=peer)
        except Exception as e:
            self.bus.emit('ERROR', 'HANDLER', f'{type(e).__name__}: {e}', conn=conn, peer=peer)
        finally:
            finished = self.connections.pop(conn, None)
            if finished:
                finished['disconnected_at'] = datetime.now(timezone.utc).isoformat()
                self.history.append(finished)
            writer.close()
            with contextlib.suppress(Exception):
                await writer.wait_closed()
            self.bus.emit('SYSTEM', 'DISCONNECTED', 'Connection closed', conn=conn, peer=peer)

    async def send(self, writer, inbound: CastMessage, destination: str, namespace: str,
                   packet: dict, conn: str, peer: str, source: str = 'receiver-0'):
        wire_msg = CastMessage(source_id=source, destination_id=destination, namespace=namespace,
                               payload_utf8=json.dumps(packet, separators=(',', ':')))
        writer.write(pack_frame(wire_msg))
        await writer.drain()
        self.bus.emit('SEND', str(packet.get('type') or 'JSON'), f'{source} → {destination}',
                      conn=conn, peer=peer, namespace=namespace, source=source,
                      target=destination, payload=packet, raw_bytes=wire_msg.encode())

    async def dispatch(self, msg: CastMessage, payload: Any, writer, conn: str, peer: str):
        if msg.namespace == NS_AUTH:
            fields = decode_fields(msg.payload_binary or b'')
            if 1 not in fields:
                raise ProtocolError('DeviceAuth message has no challenge')
            challenge = decode_fields(fields[1][-1])
            details = {'nonce_bytes': len(challenge.get(2, [b''])[-1]),
                       'signature_algorithm': challenge.get(1, [1])[-1],
                       'hash_algorithm': challenge.get(3, [0])[-1]}
            if conn in self.connections:
                self.connections[conn]['phase'] = 'AUTH_BLOCKED'
            self.bus.emit('ERROR', 'AUTH_BLOCKED', 'No Google-trusted device certificate; official sender authentication cannot succeed',
                          conn=conn, peer=peer, namespace=msg.namespace, payload=details)
            # DeviceAuthMessage.error = AuthError(INTERNAL_ERROR). Never forge success.
            reply = CastMessage('receiver-0', msg.source_id, NS_AUTH,
                                payload_binary=field_bytes(3, field_varint(1, 0)))
            writer.write(pack_frame(reply))
            await writer.drain()
            self.bus.emit('SEND', 'AUTH_ERROR', 'DeviceAuth INTERNAL_ERROR (no trusted credentials)',
                          conn=conn, peer=peer, namespace=msg.namespace)
            return
        if not isinstance(payload, dict):
            return
        typ = payload.get('type')
        request_id = payload.get('requestId')
        if msg.namespace == NS_CONNECTION:
            # CONNECT and CLOSE are virtual-channel controls, not request/response messages.
            return
        if msg.namespace == NS_HEARTBEAT and typ == 'PING':
            await self.send(writer, msg, msg.source_id, NS_HEARTBEAT,
                            {'type': 'PONG'}, conn, peer)
            return
        if msg.namespace == NS_RECEIVER:
            if typ == 'GET_STATUS':
                response = self.fake.receiver_status(request_id)
            elif typ == 'GET_APP_AVAILABILITY':
                apps = payload.get('appId', [])
                if isinstance(apps, str):
                    apps = [apps]
                response = {'type': 'GET_APP_AVAILABILITY',
                            'availability': {str(x): 'APP_AVAILABLE' for x in apps[:100]}}
                if request_id is not None:
                    response['requestId'] = request_id
            elif typ == 'LAUNCH':
                if conn in self.connections:
                    self.connections[conn]['phase'] = 'MOCK_LAUNCH'
                self.fake.app_id = str(payload.get('appId') or 'CC1AD845')[:100]
                self.fake.session_id = uuid.uuid4().hex
                self.fake.transport_id = 'web-' + uuid.uuid4().hex[:8]
                response = self.fake.receiver_status(request_id)
                self.bus.emit('SYSTEM', 'MOCK_LAUNCH', f'Fake launch: {self.fake.app_id} (no app executed)',
                              conn=conn, peer=peer)
            elif typ == 'STOP':
                self.fake.app_id = self.fake.session_id = self.fake.transport_id = None
                self.fake.media = None
                self.fake.player_state = 'IDLE'
                response = self.fake.receiver_status(request_id)
            elif typ == 'SET_VOLUME':
                v = payload.get('volume', {})
                if isinstance(v, dict):
                    if isinstance(v.get('level'), (int, float)):
                        self.fake.volume = max(0.0, min(1.0, float(v['level'])))
                    if isinstance(v.get('muted'), bool):
                        self.fake.muted = v['muted']
                response = self.fake.receiver_status(request_id)
            else:
                return
            await self.send(writer, msg, msg.source_id, NS_RECEIVER, response, conn, peer)
        elif msg.namespace == NS_MEDIA:
            if typ == 'LOAD':
                if conn in self.connections:
                    self.connections[conn]['phase'] = 'MOCK_LOAD'
                self.fake.media = redact(payload.get('media', {}))
                self.fake.media_session_id += 1
                self.fake.position = 0
                # We deliberately do not claim a real player is running.
                self.fake.player_state = 'IDLE'
                self.bus.emit('SYSTEM', 'MOCK_MEDIA', 'LOAD captured; playback intentionally disabled',
                              conn=conn, peer=peer)
            elif typ in ('PLAY', 'PAUSE', 'SEEK', 'STOP', 'GET_STATUS'):
                if typ == 'SEEK' and isinstance(payload.get('currentTime'), (int, float)):
                    self.fake.position = float(payload['currentTime'])
                if typ == 'STOP':
                    self.fake.player_state = 'IDLE'
            else:
                return
            await self.send(writer, msg, msg.source_id, NS_MEDIA,
                            self.fake.media_status(request_id), conn, peer,
                            source=self.fake.transport_id or 'receiver-0')


def parser():
    p = argparse.ArgumentParser(description='Local Cast V2 diagnostic receiver (not Google-certified)')
    p.add_argument('--name', default='Cast Debug Receiver')
    p.add_argument('--ip', help='LAN IPv4 address to advertise via mDNS (e.g. 192.168.0.5)')
    p.add_argument('--listen', default='0.0.0.0', help='Cast listener bind address')
    p.add_argument('--cast-port', type=int, default=8009)
    p.add_argument('--ui-port', type=int, default=8765)
    p.add_argument('--log-dir', default='logs')
    p.add_argument('--open-browser', action='store_true', help='Open the local UI on startup')
    p.add_argument('--no-mdns', action='store_true', help='Disable LAN device advertisement')
    p.add_argument('--include-raw', action='store_true', help='UNSAFE: record complete unredacted wire frames')
    return p


async def async_main(argv=None):
    args = parser().parse_args(argv)
    instance = CastInspector(args)
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        with contextlib.suppress(NotImplementedError, RuntimeError):
            loop.add_signal_handler(sig, instance.closing.set)
    await instance.run()


def main():
    try:
        asyncio.run(async_main())
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()
