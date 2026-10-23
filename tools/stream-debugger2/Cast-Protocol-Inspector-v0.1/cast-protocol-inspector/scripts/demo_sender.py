"""Deterministic test sender. Disables TLS verification for THIS local test only."""
from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
import ssl
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cast_inspector.protocol import CastMessage, pack_frame, MAX_FRAME_BYTES
from cast_inspector.server import NS_CONNECTION, NS_HEARTBEAT, NS_RECEIVER, NS_MEDIA


def send(writer, namespace: str, command: dict, *, dest='receiver-0'):
    packet = CastMessage('sender-debug', dest, namespace, payload_utf8=json.dumps(command))
    writer.write(pack_frame(packet))


async def run(host: str, port: int):
    # Untrusted self-signed TLS cert is expected for local debugging, not for general use.
    ctx = ssl._create_unverified_context()
    reader, writer = await asyncio.open_connection(host, port, ssl=ctx)
    print(f'TLS connected: {host}:{port}')
    send(writer, NS_CONNECTION, {'type': 'CONNECT', 'origin': {}})
    send(writer, NS_HEARTBEAT, {'type': 'PING'})
    send(writer, NS_RECEIVER, {'type': 'GET_STATUS', 'requestId': 10})
    send(writer, NS_RECEIVER, {'type': 'LAUNCH', 'appId': 'CC1AD845', 'requestId': 11})
    await writer.drain()
    responses = []
    transport_id = None
    while len(responses) < 3:
        head = await asyncio.wait_for(reader.readexactly(4), timeout=5)
        n = int.from_bytes(head, 'big')
        if not 0 < n < MAX_FRAME_BYTES:
            raise RuntimeError('Unexpected frame length')
        pkt = CastMessage.decode(await reader.readexactly(n))
        data = json.loads(pkt.payload_utf8 or '{}')
        responses.append(data['type'])
        print('RECV', pkt.namespace.rsplit('.', 1)[-1], json.dumps(data, ensure_ascii=False)[:320])
        if data['type'] == 'RECEIVER_STATUS' and data.get('status', {}).get('applications'):
            transport_id = data['status']['applications'][0]['transportId']
    assert 'PONG' in responses, 'no heartbeat PONG response'
    assert responses.count('RECEIVER_STATUS') == 2, 'status responses missing'
    if not transport_id:
        raise AssertionError('mock LAUNCH did not return transportId')
    send(writer, NS_CONNECTION, {'type': 'CONNECT'}, dest=transport_id)
    send(writer, NS_MEDIA, {'type': 'LOAD', 'requestId': 12, 'media': {
        'contentId': 'https://example.org/test.mp3', 'contentType': 'audio/mpeg',
        'streamType': 'BUFFERED'
    }}, dest=transport_id)
    await writer.drain()
    head = await asyncio.wait_for(reader.readexactly(4), timeout=5)
    pkt = CastMessage.decode(await reader.readexactly(int.from_bytes(head, 'big')))
    media = json.loads(pkt.payload_utf8 or '{}')
    assert media.get('type') == 'MEDIA_STATUS', media
    print('RECV media:', json.dumps(media, ensure_ascii=False)[:320])
    writer.close()
    await writer.wait_closed()
    print('PASS: TLS / CONNECT / PING-PONG / GET_STATUS / LAUNCH / LOAD verified (mock playback only)')


def main():
    p = argparse.ArgumentParser(description='Run Cast V2 test packets against a local debug receiver')
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=8009)
    args = p.parse_args()
    asyncio.run(run(args.host, args.port))


if __name__ == '__main__':
    main()
