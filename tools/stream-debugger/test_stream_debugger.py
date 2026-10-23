import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from stream_debugger import Monitor, inspect_aac, parse_playlist


def frame():
    size = 9
    return bytes((255, 241, 76, 128, size >> 3, ((size & 7) << 5) | 31, 252, 1, 2))


PLAYLIST = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:4.010667,\nseg-7.aac\n#EXT-X-ENDLIST\n"


class Checks(unittest.TestCase):
    def test_aac_with_id3_and_truncation(self):
        data = b"ID3\x04\x00\x00\x00\x00\x00\x00" + frame() * 188
        frames, seconds = inspect_aac(data)
        self.assertEqual(frames, 188)
        self.assertAlmostEqual(seconds, 4.0106666667)
        with self.assertRaises(ValueError):
            inspect_aac(data[:-1])

    def test_wrong_format_and_empty(self):
        for data in (b"", b"HTML", bytes((255, 241, 80, 128, 1, 63, 252, 1, 2))):
            with self.assertRaises(ValueError):
                inspect_aac(data)

    def test_playlist(self):
        parsed = parse_playlist(PLAYLIST)
        self.assertEqual(parsed.segments[0], (7, 4.010667, "seg-7.aac"))
        self.assertTrue(parsed.ended)
        for invalid in ("HTML", PLAYLIST.replace("4.010667", "nan"), PLAYLIST.replace("#EXTINF:4.010667,\n", "")):
            with self.assertRaises(ValueError):
                parse_playlist(invalid)

    def test_http_preparation_then_receive(self):
        class Handler(BaseHTTPRequestHandler):
            calls = 0

            def do_GET(self):
                if self.path.endswith("live.m3u8"):
                    Handler.calls += 1
                    if Handler.calls == 1:
                        self.send_response(503)
                        self.end_headers()
                        return
                    body = PLAYLIST.encode()
                else:
                    body = frame() * 188
                self.send_response(200)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        events = []
        try:
            monitor = Monitor(f"http://127.0.0.1:{server.server_port}/token/live.m3u8", threading.Event(), lambda *event: events.append(event))
            monitor.run()
            self.assertTrue(any("503" in value for _, value in events))
            self.assertTrue(any("정상 #7" in value for _, value in events))
            self.assertTrue(any("수신 성공 1개" in value for _, value in events))
            self.assertFalse(any("token" in value for _, value in events))
            with self.assertRaises(ValueError):
                monitor.fetch("http://example.org/other.aac")
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
