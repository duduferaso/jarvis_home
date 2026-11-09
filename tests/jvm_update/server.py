import http.server, sys
LOG = []
DATA = bytes(range(256)) * 781 + bytes(64)  # 200000 bytes
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        auth = "yes" if self.headers.get("Authorization") else "no"
        if self.path.startswith("/api/asset/"):
            LOG.append("asset:auth=" + auth)
            self.send_response(302); self.send_header("Location", "http://localhost:%d/file" % PORT); self.end_headers()
        elif self.path.startswith("/download/") or self.path == "/file":
            LOG.append("file:auth=" + auth)
            self.send_response(200); self.send_header("Content-Length", str(len(DATA))); self.end_headers(); self.wfile.write(DATA)
        elif self.path == "/log":
            b = " ".join(LOG).encode(); self.send_response(200); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
        else:
            self.send_response(404); self.end_headers()
PORT = int(sys.argv[1])
http.server.HTTPServer(("127.0.0.1", PORT), H).serve_forever()
