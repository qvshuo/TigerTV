#!/usr/bin/env python3
"""Offline, process-level compatibility tests. Python is a test oracle, never a runtime dependency."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[1]


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        parsed = urlsplit(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == "/proxy":
            query = parse_qs(urlsplit(query["url"][0]).query)
        host = f"http://127.0.0.1:{self.server.server_port}"
        if parsed.path == "/bad":
            data = {"code": 1, "list": [None]}
        elif parsed.path == "/oversized":
            body = b"x" * (10 * 1024 * 1024 + 1)
            return self.send(body)
        elif parsed.path == "/page":
            return self.send(b'<script>var src="/master.m3u8?sign=a%2Fb%2Bc%3D";</script>')
        elif parsed.path == "/master.m3u8":
            return self.send(b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchild.m3u8\n")
        elif parsed.path == "/child.m3u8":
            return self.send(b'#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="https://keys.example/a.key"\nhttps://segments.example/1.ts\n')
        elif query.get("ac") == ["detail"]:
            data = {"code": 1.0, "list": [{"vod_id": 999, "vod_pic": host + "/cover.jpg", "vod_play_url": f" 第一集 ${host}/page#第二集${host}/two.mp4$$$备用${host}/master.m3u8", "vod_down_url": ""}]}
        else:
            data = {"code": "1", "list": [{"vod_id": "42", "vod_name": "逐玉🎬", "vod_pic": None}, {"vod_id": 2.9, "vod_name": "数字", "vod_time": "2026"}, {"vod_id": "1.5", "vod_name": "异常 ID"}]}
        self.send(json.dumps(data, ensure_ascii=False).encode())

    def send(self, body):
        try:
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=Path)
    args = parser.parse_args()
    native = [str(args.binary.resolve())]
    python = [sys.executable, str(ROOT / "tigertv-cli.py")]
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="tigertv-parity-中文-") as temp:
            host = f"http://127.0.0.1:{server.server_port}"
            source = Path(temp) / "来源🎬.json"
            source.write_text(json.dumps({"api_site": {
                "good": {"name": "🎬站点", "api": host + "/api"},
                "proxy": {"name": "🎬代理", "api": host + "/proxy?url=" + host + "/api"},
                "duplicate": {"name": "🎬重复", "api": host + "/api"},
                "disabled": {"name": "🎬禁用", "api": host + "/disabled", "_comment": None},
                "bad": {"name": "🎬坏响应", "api": host + "/bad"},
                "oversized": {"name": "🎬超大响应", "api": host + "/oversized"},
            }}, ensure_ascii=False), encoding="utf-8")

            def run(command, arguments, source_enabled=True):
                env = os.environ.copy()
                # Native CLI must work with no JVM/Python discoverable through PATH.
                if command == native:
                    env["PATH"] = temp
                    env.pop("JAVA_HOME", None)
                return subprocess.run(command + (["--source", str(source)] if source_enabled else []) + arguments,
                                      capture_output=True, encoding="utf-8", env=env, timeout=45)

            for arguments in (["search", " 中文🎬 "], ["fetch", "--site", "🎬站点", "--vod_id", "42"], ["quanx", "逐玉"]):
                expected = run(python, arguments)
                actual = run(native, arguments)
                assert (expected.returncode, actual.returncode) == (0, 0), (arguments, expected.stderr, actual.stderr)
                assert not actual.stderr, actual.stderr
                if arguments[0] == "quanx":
                    assert actual.stdout == expected.stdout, (expected.stdout, actual.stdout)
                else:
                    assert json.loads(actual.stdout) == json.loads(expected.stdout), (expected.stdout, actual.stdout)
            for arguments, code in [(["fetch", "--site", "不存在", "--vod_id", "42"], 1), (["fetch", "--site", "🎬坏响应", "--vod_id", "42"], 1), (["fetch", "--vod_id", "x"], 2), (["nonsense"], 2)]:
                actual = run(native, arguments)
                assert actual.returncode == code and not actual.stdout and actual.stderr, (arguments, actual)
                assert "Traceback" not in actual.stderr
            assert run(native, ["--version"], False).returncode == 0
            assert run(native, ["--help"], False).returncode == 0
            assert run(native, ["logs"], False).returncode == 0
            closed_pipe = subprocess.Popen(native + ["--source", str(source), "search", "fixture"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            closed_pipe.stdout.close()
            assert closed_pipe.wait(timeout=45) == 0
            assert not closed_pipe.stderr.read()
            print("Native CLI parity passed: search, fetch, quanx, Unicode, proxy URLs, response cap, errors, help/version/logs, closed pipe; PATH has no JVM/Python.")
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
