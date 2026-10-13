"""Local HTTP server for the current usage snapshot."""

import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlsplit

from .snapshot import collect_snapshot


def make_handler(collector=collect_snapshot):
    """Return a request handler with a 60 second snapshot cache."""
    cache = {"at": 0, "body": b""}

    class UsageHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            """Serve usage JSON and reject unknown paths."""
            if urlsplit(self.path).path != "/usage.json":
                self.send_error(404)
                return
            if time.time() - cache["at"] >= 60:
                cache["body"] = json.dumps(
                    collector(), separators=(",", ":")
                ).encode("utf-8")
                cache["at"] = time.time()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(cache["body"])

        def log_message(self, *_args):
            pass

    return UsageHandler


def serve(host="127.0.0.1", port=8765):
    """Serve the cached usage snapshot until interrupted."""
    server = HTTPServer((host, port), make_handler())
    server.serve_forever()
