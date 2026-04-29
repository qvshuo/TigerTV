import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
import urllib.parse
from contextlib import ExitStack
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "tigertv-cli.py"


def load_module():
    spec = importlib.util.spec_from_file_location("tigertv_cli", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class TigerTvCliTests(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run(
            ["python3", str(SCRIPT_PATH), *args],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

    def run_main(self, module, argv, make_request=None, http_get=None):
        stdout = io.StringIO()
        stderr = io.StringIO()
        patches = [
            mock.patch.object(sys, "argv", ["tigertv-cli.py", *argv]),
            mock.patch("sys.stdout", stdout),
            mock.patch("sys.stderr", stderr),
        ]
        if make_request is not None:
            patches.append(mock.patch.object(module, "make_request", side_effect=make_request))
        if http_get is not None:
            patches.append(mock.patch.object(module, "_http_get", side_effect=http_get))

        with ExitStack() as stack:
            for patch in patches:
                stack.enter_context(patch)
            module.main()

        return stdout.getvalue(), stderr.getvalue()

    def write_source_config(self, directory, api_url):
        source_path = Path(directory) / "source.json"
        payload = {
            "api_site": {
                "demo": {
                    "name": "🎬-测试站-",
                    "api": api_url,
                    "detail": api_url,
                }
            }
        }
        source_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        return source_path

    def test_logs_command_does_not_require_config(self):
        result = self.run_cli("logs", "--clear")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stderr, "")
        self.assertIn("日志已清空", result.stdout)

    def test_error_output_goes_to_stderr(self):
        result = self.run_cli("--source", "missing.json", "search", "逐玉")
        self.assertEqual(result.returncode, 1)
        self.assertIn("错误: 读取 source 失败", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_search_fetch_and_quanx_cli_flow(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            source = self.write_source_config(
                temp_dir, "http://media.test/api.php/provide/vod"
            )

            def fake_make_request(url, timeout=10):
                if "ac=list" in url:
                    return {
                        "code": 1,
                        "msg": "ok",
                        "list": [
                            {
                                "vod_id": 101,
                                "vod_name": "逐玉",
                                "vod_time": "2026-04-28",
                                "vod_remarks": "全40集",
                            }
                        ],
                    }
                if "ac=detail" in url:
                    return {
                        "code": 1,
                        "msg": "ok",
                        "list": [
                            {
                                "vod_play_url": "第01集$http://media.test/play-page",
                                "vod_down_url": "下载1$http://media.test/media/direct.m3u8",
                            }
                        ],
                    }
                raise AssertionError(f"unexpected url: {url}")

            def fake_http_get(url, timeout=10):
                responses = {
                    "http://media.test/play-page": b'<script>var u="/media/master.m3u8?sign=abc";</script>',
                    "http://media.test/media/master.m3u8?sign=abc": b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nvariant.m3u8\n",
                    "http://media.test/media/variant.m3u8": b"#EXTM3U\nhttp://cdn-a.test/seg1.ts\n/media/seg2.ts\n",
                    "http://media.test/media/direct.m3u8": b"#EXTM3U\nhttp://cdn-b.test/direct-seg.ts\n",
                }
                try:
                    return responses[url]
                except KeyError as exc:
                    raise AssertionError(f"unexpected url: {url}") from exc

            out, err = self.run_main(
                module,
                ["--source", str(source), "search", "逐玉"],
                make_request=fake_make_request,
            )
            search_payload = json.loads(out)
            self.assertEqual(search_payload["keyword"], "逐玉")
            self.assertEqual(search_payload["results"][0]["site"], "🎬-测试站-")
            self.assertEqual(search_payload["results"][0]["vod_id"], 101)

            out, err = self.run_main(
                module,
                [
                    "--source",
                    str(source),
                    "fetch",
                    "--site",
                    "🎬-测试站-",
                    "--vod_id",
                    "101",
                ],
                make_request=fake_make_request,
            )
            fetch_payload = json.loads(out)
            self.assertEqual(fetch_payload["vod_play_url"][0]["name"], "第01集")
            self.assertEqual(
                fetch_payload["vod_play_url"][0]["url"], "http://media.test/play-page"
            )
            self.assertEqual(
                fetch_payload["vod_down_url"][0]["url"],
                "http://media.test/media/direct.m3u8",
            )

            out, err = self.run_main(
                module,
                ["--source", str(source), "quanx", "逐玉"],
                make_request=fake_make_request,
                http_get=fake_http_get,
            )
            self.assertIn("; 资源站 API 域名", out)
            self.assertIn("host-suffix, media.test, direct", out)
            self.assertIn("host-suffix, cdn-a.test, direct", out)
            self.assertIn("host-suffix, cdn-b.test, direct", out)


class TigerTvConfigTests(unittest.TestCase):
    def test_parse_config_rejects_invalid_top_level(self):
        module = load_module()
        with self.assertRaises(module.ConfigError):
            module._parse_config([])

    def test_require_api_sites_raises_on_empty(self):
        module = load_module()
        with self.assertRaises(module.ConfigError):
            module.require_api_sites({})

    def test_load_config_from_source_file(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            source_path = Path(temp_dir) / "source.json"
            payload = {
                "api_site": {
                    "demo": {
                        "name": "🎬-本地站-",
                        "api": "http://local.test/api",
                    }
                }
            }
            source_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
            api_name_map = module.load_config(source=str(source_path))
            self.assertEqual(api_name_map, {"http://local.test/api": "🎬-本地站-"})

    def test_load_config_falls_back_to_stale_cache(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            cache_path = Path(temp_dir) / "cache.json"
            cached_config = {
                "fetched_at": "2000-01-01T00:00:00",
                "config": {
                    "api_site": {
                        "demo": {
                            "name": "🎬-缓存站-",
                            "api": "http://cache.test/api.php/provide/vod",
                        }
                    }
                },
            }
            cache_path.write_text(
                json.dumps(cached_config, ensure_ascii=False), encoding="utf-8"
            )

            original_cache = module.CACHE_FILE
            original_make_request = module.make_request
            module.CACHE_FILE = str(cache_path)
            module.make_request = lambda *args, **kwargs: (_ for _ in ()).throw(
                module.RequestError("network down")
            )
            try:
                api_name_map = module.load_config()
            finally:
                module.CACHE_FILE = original_cache
                module.make_request = original_make_request

            self.assertEqual(
                api_name_map,
                {"http://cache.test/api.php/provide/vod": "🎬-缓存站-"},
            )


class TigerTvRequestTests(unittest.TestCase):
    def test_check_response_raises_on_non_code_1(self):
        module = load_module()
        with self.assertRaises(module.RequestError):
            module.check_response({"code": 0, "msg": "error"}, "test-site")

    def test_check_response_passes_on_code_1(self):
        module = load_module()
        data = {"code": 1, "msg": "ok", "list": []}
        result = module.check_response(data, "test-site")
        self.assertEqual(result, data)

    def test_check_response_passes_when_no_code(self):
        module = load_module()
        data = {"list": []}
        result = module.check_response(data, "test-site")
        self.assertEqual(result, data)

    def test_build_api_url_appends_to_existing_query(self):
        module = load_module()
        result = module.build_api_url("https://api.test/vod?token=abc", wd="逐玉", ac="list")
        self.assertIn("token=abc", result)
        self.assertIn("wd=%E9%80%90%E7%8E%89", result)
        self.assertIn("ac=list", result)
        self.assertEqual(result.count("?"), 1)

    def test_build_api_url_preserves_proxy_url_parameter(self):
        module = load_module()
        result = module.build_api_url(
            "https://proxy.test/?url=https://origin.test/api.php/provide/vod",
            wd="逐玉",
            ac="list",
        )
        parsed = urllib.parse.urlsplit(result)
        query = dict(urllib.parse.parse_qsl(parsed.query))
        inner = query["url"]
        self.assertTrue(inner.startswith("https://origin.test/api.php/provide/vod?"))
        self.assertIn("wd=%E9%80%90%E7%8E%89", inner)
        self.assertIn("ac=list", inner)

    def test_request_vod_list_rejects_non_list_value(self):
        module = load_module()
        with mock.patch.object(module, "make_request", return_value={"code": 1, "list": ""}):
            with self.assertRaises(module.RequestError):
                module.request_vod_list("https://api.test/vod", "test", wd="逐玉")

    def test_clean_domain_basic(self):
        module = load_module()
        self.assertEqual(module.clean_domain("https://www.example.com/path"), "www.example.com")
        self.assertEqual(module.clean_domain("http://cdn.test:8080/video"), "cdn.test")
        self.assertEqual(module.clean_domain("plain.host"), "plain.host")

    def test_encode_url_preserves_ascii(self):
        module = load_module()
        self.assertEqual(
            module._encode_url("https://example.com/search?q=test"),
            "https://example.com/search?q=test",
        )

    def test_encode_url_encodes_non_ascii(self):
        module = load_module()
        result = module._encode_url("https://example.com/搜索?q=测试")
        self.assertIn("%", result)
        self.assertNotIn("搜索", result)


class TigerTvPlayUrlTests(unittest.TestCase):
    def test_parse_play_urls_all(self):
        module = load_module()
        raw = "第01集$http://a.com/1.m3u8#第02集$http://a.com/2.m3u8$$$第03集$http://b.com/3.m3u8"
        result = module.parse_play_urls(raw)
        self.assertEqual(len(result), 3)
        self.assertEqual(result[0], ("第01集", "http://a.com/1.m3u8"))
        self.assertEqual(result[2], ("第03集", "http://b.com/3.m3u8"))

    def test_parse_play_urls_first_only(self):
        module = load_module()
        raw = "第01集$http://a.com/1.m3u8#第02集$http://a.com/2.m3u8$$$第03集$http://b.com/3.m3u8"
        result = module.parse_play_urls(raw, first_only=True)
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0], ("第01集", "http://a.com/1.m3u8"))
        self.assertEqual(result[1], ("第03集", "http://b.com/3.m3u8"))

    def test_parse_play_urls_empty(self):
        module = load_module()
        self.assertEqual(module.parse_play_urls(""), [])
        self.assertEqual(module.parse_play_urls(None), [])


class TigerTvM3u8Tests(unittest.TestCase):
    def test_resolve_m3u8_direct_url(self):
        module = load_module()
        result = module.resolve_m3u8_urls("https://cdn.test/video.m3u8", "test")
        self.assertEqual(result, ["https://cdn.test/video.m3u8"])

    def test_resolve_m3u8_from_html(self):
        module = load_module()
        html_content = b'<script>var u="/path/stream.m3u8?token=abc";</script>'
        with mock.patch.object(module, "_http_get", return_value=html_content):
            result = module.resolve_m3u8_urls("https://cdn.test/play", "test")
        self.assertEqual(len(result), 1)
        self.assertIn("stream.m3u8", result[0])

    def test_resolve_m3u8_from_escaped_html(self):
        module = load_module()
        html_content = b'<script>var u="https:\\/\\/cdn.test\\/path\\/stream.m3u8?token=abc";</script>'
        with mock.patch.object(module, "_http_get", return_value=html_content):
            result = module.resolve_m3u8_urls("https://play.test/page", "test")
        self.assertEqual(result, ["https://cdn.test/path/stream.m3u8?token=abc"])

    def test_resolve_m3u8_content_directly(self):
        module = load_module()
        m3u8_content = b"#EXTM3U\n#EXTINF:10,\nhttp://cdn.test/seg.ts\n"
        with mock.patch.object(module, "_http_get", return_value=m3u8_content):
            result = module.resolve_m3u8_urls("https://play.test/page", "test")
        self.assertEqual(result, ["https://play.test/page"])

    def test_fetch_m3u8_domains_collects_master_and_segments(self):
        module = load_module()
        responses = {
            "http://media.test/master.m3u8": b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nvariant.m3u8\n",
            "http://media.test/variant.m3u8": b"#EXTM3U\nhttp://cdn-a.test/seg1.ts\n/seg2.ts\n",
        }
        with mock.patch.object(
            module, "_http_get", side_effect=lambda url, timeout=10: responses[url]
        ):
            domains = module.fetch_m3u8_domains("http://media.test/master.m3u8", "test")
        self.assertEqual(domains, {"media.test", "cdn-a.test"})

    def test_fetch_m3u8_domains_collects_key_domain(self):
        module = load_module()
        content = b'#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="https://key.test/key.bin"\nsegment.ts\n'
        with mock.patch.object(module, "_http_get", return_value=content):
            domains = module.fetch_m3u8_domains("http://media.test/video.m3u8", "test")
        self.assertEqual(domains, {"media.test", "key.test"})


class TigerTvLogTests(unittest.TestCase):
    def test_trim_log_file_truncates_when_too_long(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            log_path = Path(temp_dir) / "test.log"
            original_log_file = module.LOG_FILE
            original_max_lines = module.LOG_MAX_LINES
            original_keep_lines = module.LOG_KEEP_LINES
            module.LOG_FILE = str(log_path)
            module.LOG_MAX_LINES = 10
            module.LOG_KEEP_LINES = 3
            try:
                log_path.write_text("\n".join(f"line{i}" for i in range(12)) + "\n", encoding="utf-8")
                module._trim_log_file()
                lines = log_path.read_text(encoding="utf-8").splitlines()
                self.assertEqual(len(lines), 3)
                self.assertEqual(lines[0], "line9")
                self.assertEqual(lines[-1], "line11")
            finally:
                module.LOG_FILE = original_log_file
                module.LOG_MAX_LINES = original_max_lines
                module.LOG_KEEP_LINES = original_keep_lines


if __name__ == "__main__":
    unittest.main(verbosity=2)
