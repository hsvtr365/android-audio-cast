import unittest
from cast_inspector.protocol import (CastMessage, ProtocolError, pack_frame,
                                     decode_fields, read_varint, varint)
from cast_inspector.server import FakeReceiverState, redact, NS_MEDIA


class ProtocolTest(unittest.TestCase):
    def test_roundtrip_text(self):
        p = CastMessage('sender-0', 'receiver-0', 'urn:x-cast:com.google.cast.tp.heartbeat',
                        payload_utf8='{"type":"PING"}')
        self.assertEqual(CastMessage.decode(p.encode()), p)
        self.assertEqual(int.from_bytes(pack_frame(p)[:4], 'big'), len(p.encode()))

    def test_roundtrip_binary(self):
        p = CastMessage('sender-0', 'receiver-0', 'urn:x-cast:com.google.cast.tp.deviceauth',
                        payload_binary=b'\x00\x01\x02')
        self.assertEqual(CastMessage.decode(p.encode()), p)

    def test_varint(self):
        for n in [0, 127, 128, 255, 300, 0xffffffff]:
            raw = varint(n)
            self.assertEqual(read_varint(raw, 0), (n, len(raw)))

    def test_invalid_wire(self):
        with self.assertRaises(ProtocolError):
            CastMessage.decode(b'\x00')
        with self.assertRaises(ProtocolError):
            CastMessage.decode(b'\x0a\xff')

    def test_optional_unknown_field(self):
        from cast_inspector.protocol import field_bytes
        p = CastMessage('a', 'b', 'urn:demo', payload_utf8='{}')
        self.assertEqual(CastMessage.decode(p.encode() + field_bytes(10, b'ignored')), p)

    def test_redaction(self):
        self.assertEqual(redact({'token': 'abcdef', 'nested': [{'Authorization': 'abc'}, 'okay']}),
                         {'token': '[REDACTED]', 'nested': [{'Authorization': '[REDACTED]'}, 'okay']})

    def test_receiver_mock(self):
        s = FakeReceiverState()
        self.assertEqual(s.receiver_status(1)['requestId'], 1)
        self.assertEqual(s.media_status(2)['status'][0]['playerState'], 'IDLE')
        self.assertEqual(NS_MEDIA, 'urn:x-cast:com.google.cast.media')

    def test_url_redaction(self):
        url = 'http://user:password@192.168.0.28:8080/' + 'a' * 32 + '/live.m3u8?auth=private'
        result = redact({'contentId': url})['contentId']
        self.assertNotIn('password', result)
        self.assertNotIn('private', result)
        self.assertNotIn('a' * 32, result)
        self.assertIn('live.m3u8', result)

    def test_missing_payload(self):
        from cast_inspector.protocol import field_bytes, field_varint
        raw = (field_varint(1, 0) + field_bytes(2, b'a') + field_bytes(3, b'b')
               + field_bytes(4, b'urn:test') + field_varint(5, 0))
        with self.assertRaises(ProtocolError):
            CastMessage.decode(raw)

    def test_invalid_advertised_address(self):
        from cast_inspector.discovery import select_ip
        for address in ('224.0.0.251', '0.0.0.0', '169.254.1.2'):
            with self.assertRaises(ValueError):
                select_ip(address)
        self.assertEqual(select_ip('192.168.0.5'), '192.168.0.5')


if __name__ == '__main__':
    unittest.main()
