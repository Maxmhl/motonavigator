#!/usr/bin/env python3
"""
Einfacher HTTP-Server mit Range-Unterstützung (fortsetzbare Downloads) für den
Einrichtungs-Assistenten im heimischen WLAN.

    python3 serve.py out/ 8080
    → Basis-URL in der App: http://<PC-IP>:8080/
"""
import http.server
import os
import re
import sys


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    def send_head(self):
        path = self.translate_path(self.path)
        rng = self.headers.get("Range")
        if not rng or not os.path.isfile(path):
            return super().send_head()
        m = re.match(r"bytes=(\d+)-(\d*)", rng)
        if not m:
            return super().send_head()
        size = os.path.getsize(path)
        start = int(m.group(1))
        end = int(m.group(2)) if m.group(2) else size - 1
        if start >= size:
            self.send_response(416)
            self.send_header("Content-Range", "bytes */%d" % size)
            self.end_headers()
            return None
        f = open(path, "rb")
        f.seek(start)
        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()
        self._remaining = end - start + 1
        return f

    def copyfile(self, source, outputfile):
        remaining = getattr(self, "_remaining", None)
        if remaining is None:
            return super().copyfile(source, outputfile)
        while remaining > 0:
            chunk = source.read(min(1 << 16, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else "out"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
    os.chdir(root)
    print("Serviere %s auf Port %d (Range-fähig)" % (os.getcwd(), port))
    http.server.ThreadingHTTPServer(("0.0.0.0", port), RangeHandler).serve_forever()
