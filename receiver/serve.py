"""LAN-only development receiver host; no audio or filesystem upload endpoint."""
import argparse
import json
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        target = urlsplit(self.path)
        if target.path == '/event':
            try:
                data = json.loads(parse_qs(target.query).get('data', ['{}'])[0])
                if len(target.query) > 4096 or not isinstance(data, dict):
                    raise ValueError('invalid event')
                print(json.dumps({'peer': self.client_address[0], **{
                    key: data[key] for key in ('event', 'elapsed', 'position',
                    'paused', 'readyState', 'buffered', 'error') if key in data
                }}), flush=True)
                self.send_response(204)
                self.end_headers()
            except (ValueError, TypeError):
                self.send_error(400)
        elif target.path in ('/', '/index.html'):
            super().do_GET()
        else:
            self.send_error(404)

    def log_message(self, format, *args):
        if not self.path.startswith('/event?'):
            super().log_message(format, *args)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--bind', required=True)
    parser.add_argument('--port', type=int, default=8097)
    args = parser.parse_args()
    root = str(Path(__file__).resolve().parent / 'web')
    ThreadingHTTPServer((args.bind, args.port), partial(Handler, directory=root)).serve_forever()
