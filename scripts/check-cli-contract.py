#!/usr/bin/env python3
"""Local HTTP fixture tests for the Kotlin/Native CLI contract."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit


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
        elif query.get("ids"):
            data = {"code": 1.0, "list": [{"vod_id": 42, "vod_name": "逐玉🎬", "vod_pic": host + "/cover.jpg", "vod_play_from": "主线$$$备用", "vod_down_from": "下载", "vod_play_url": f" 第一集 ${host}/page#第二集${host}/two.mp4$$$备用${host}/master.m3u8", "vod_down_url": f"第一集${host}/one.mp4"}]}
        else:
            data = {"code": "1", "list": [{"vod_id": "42", "vod_name": "逐玉🎬", "vod_pic": None}, {"vod_id": 2.0, "vod_name": "数字", "vod_time": "2026"}]}
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
                return subprocess.run(command + (["--config", str(source)] if source_enabled else []) + arguments,
                                      capture_output=True, encoding="utf-8", env=env, timeout=45)

            search = run(native, ["search", "--query", " 中文🎬 "])
            assert search.returncode == 0 and not search.stderr, search
            payload = json.loads(search.stdout)
            assert set(payload) == {"query", "works"}, payload
            assert payload["query"] == " 中文🎬 "
            assert len(payload["works"]) == 2
            assert [len(work["sources"]) for work in payload["works"]] == [2, 2]
            assert [item["vod_id"] for work in payload["works"] for item in work["sources"]] == [42, 42, 2, 2]
            assert all(item["site"] in ("🎬站点", "🎬代理") for work in payload["works"] for item in work["sources"])
            detail = run(native, ["fetch", "--site", "🎬站点", "--video-id", "42"])
            assert detail.returncode == 0 and not detail.stderr, detail
            payload = json.loads(detail.stdout)
            assert "vod_play_url" not in payload and "vod_down_url" not in payload
            assert payload["vod_id"] == 42
            assert [line["name"] for line in payload["play_sources"]] == ["主线", "备用"]
            assert [len(line["episodes"]) for line in payload["play_sources"]] == [2, 1]
            assert payload["download_sources"][0]["episodes"][0]["url"] == host + "/one.mp4"
            quanx = run(native, ["quanx", "--query", "逐玉"])
            assert quanx.returncode == 0 and not quanx.stderr, quanx
            for domain in ("127.0.0.1", "keys.example", "segments.example"):
                assert "host-suffix, " + domain + ", direct" in quanx.stdout, quanx.stdout
            for arguments, code in [
                (["fetch", "--site", "a", "--site", "b", "--video-id", "1"], 2),
                (["fetch", "--site", "a", "--video-id", "1", "--video-id", "2"], 2),
                (["fetch", "--site", "", "--video-id", "1"], 2),
                (["--config", "", "search", "--query", "逐玉"], 2),
                (["fetch", "--site", "不存在", "--video-id", "42"], 1),
                (["fetch", "--site", "🎬坏响应", "--video-id", "42"], 1),
                (["fetch", "--video-id", "x"], 2),
                (["nonsense"], 2),
                (["search", "--wd", "逐玉"], 2),
                (["fetch", "--site", "🎬站点", "--vod_id", "42"], 2),
                (["search", "逐玉"], 2),
                (["search", "--query", ""], 2),
                (["search", "--keyword", "逐玉"], 2),
                (["quanx", "逐玉"], 2),
                (["search", "--query", "a", "--query", "b"], 2),
                (["--source", str(source), "search", "--query", "逐玉"], 2),
                (["fetch", "--site", "🎬站点", "--video-id", "0"], 2),
                (["fetch", "--site", "🎬站点", "--id", "42"], 2),
            ]:
                actual = run(native, arguments)
                assert actual.returncode == code and not actual.stdout and actual.stderr, (arguments, actual)
                assert "Traceback" not in actual.stderr
            assert run(native, ["--version"], False).returncode == 0
            assert run(native, ["--help"], False).returncode == 0
            assert run(native, ["logs"], False).returncode == 0
            closed_pipe = subprocess.Popen(native + ["--config", str(source), "search", "--query", "fixture"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            closed_pipe.stdout.close()
            assert closed_pipe.wait(timeout=45) == 0
            assert not closed_pipe.stderr.read()
            print("Native CLI contract passed: search, fetch, quanx, grouping, named playback/download lines, Unicode, proxy URLs, response cap, errors, help/version/logs, closed pipe; PATH has no JVM/Python.")
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
