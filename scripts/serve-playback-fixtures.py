#!/usr/bin/env python3
"""Serve generated H.264/AAC MP4/HLS files plus a MacCMS fixture for native GUI smoke tests.

Never serves the repository. Supply a dedicated media directory; see cross-platform.md.
"""
import argparse
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
from urllib.parse import parse_qs, urlsplit


class Handler(BaseHTTPRequestHandler):
    def __init__(self, *args, directory, **kwargs):
        self.directory = Path(directory).resolve()
        super().__init__(*args, **kwargs)

    def do_GET(self):
        parsed = urlsplit(self.path)
        base = "http://" + self.headers["Host"]
        if parsed.path == "/config":
            return self.json({"api_site": {"fixture": {"name": "🎬播放验证", "api": base + "/api"}}})
        if parsed.path == "/api":
            query = parse_qs(parsed.query)
            ids = [int(query["ids"][0])] if query.get("ids") else range(1, min(8, max(1, int(query.get("pagesize", ["100"])[0]))) + 1)
            return self.json({"code": 1, "pagecount": 1, "list": [{
                "vod_id": index, "vod_name": f"播放验证 {index}", "vod_year": "2026", "type_name": "测试剧集",
                "vod_remarks": "HLS · MP4 · 错误重试", "vod_pic": base + "/cover.png",
                "vod_play_from": "HLS$$$MP4$$$错误重试",
                "vod_play_url": f"第一集${base}/index.m3u8$$$第一集${base}/sample.mp4$$$第一集${base}/missing.m3u8"
            } for index in ids]})
        path = (self.directory / parsed.path.lstrip("/")).resolve()
        if not path.is_relative_to(self.directory) or not path.is_file() or path.suffix not in {".mp4", ".m3u8", ".ts", ".png"}:
            return self.send_error(404)
        data = path.read_bytes()
        start, end, status = 0, len(data) - 1, 200
        if "Range" in self.headers:
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers["Range"])
            if not match:
                return self.send_error(416)
            start = int(match[1]); end = min(end, int(match[2])) if match[2] else end
            if start > end:
                return self.send_error(416)
            status = 206
        content_type = {".mp4": "video/mp4", ".m3u8": "application/vnd.apple.mpegurl", ".ts": "video/mp2t", ".png": "image/png"}[path.suffix]
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        self.end_headers()
        try:
            self.wfile.write(data[start:end + 1])
        except (BrokenPipeError, ConnectionResetError):
            pass

    def json(self, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18766)
    args = parser.parse_args()
    with ThreadingHTTPServer((args.bind, args.port), partial(Handler, directory=args.directory)) as server:
        print(f"Playback fixtures: http://{args.bind}:{args.port}/config", flush=True)
        server.serve_forever()
