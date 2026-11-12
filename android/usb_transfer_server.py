"""Temporary localhost-only repository server for this USB setup session."""
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPORTS = ROOT.parent / "results" / "android"
ALLOWED_REPORTS = {"install-plan", "install-result", "gnss-result", "host-plan", "host-install", "host-runtime"}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT / "repo"), **kwargs)

    def do_POST(self):
        name = self.path.removeprefix("/report/")
        if self.path != "/report/" + name or name not in ALLOWED_REPORTS:
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            self.send_error(400)
            return
        if not 0 <= length <= 1_000_000:
            self.send_error(413)
            return
        data = self.rfile.read(length)
        if len(data) != length:
            self.send_error(400)
            return
        REPORTS.mkdir(parents=True, exist_ok=True)
        (REPORTS / (name + ".txt")).write_bytes(data)
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"Saved locally on Mac\n")


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 18765), Handler).serve_forever()
