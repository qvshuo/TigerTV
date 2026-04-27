#!/usr/bin/env python3
"""
小老虎爱看剧 (TigerTV)
命令行入口：tiger-tv.py <command> [options]
"""

import argparse
import datetime
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

# ============== 基础配置 ==============

CONFIG_URL = "https://raw.githubusercontent.com/hafrey1/LunaTV-config/refs/heads/main/LunaTV-config.json"
USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15"
MAX_RESPONSE_SIZE = 10 * 1024 * 1024  # 10MB
LOG_FILE = "/tmp/tiger-tv.log"

_log_lock = Lock()

# ============== 异常类型 ==============


class ConfigError(Exception):
    pass


class FetchError(Exception):
    pass


class RequestError(Exception):
    pass


# ============== 日志与基础工具 ==============


def _log(level, context, message):
    """将日志写入文件，每条带时间戳和级别。

    level: INFO | WARN | ERROR
    context: 来源名或模块标识
    message: 日志正文
    """
    timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"{timestamp} [{level}] [{context}]: {message}\n"
    with _log_lock:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line)


def _encode_url(url):
    """对 URL 中的非 ASCII 字符做 percent-encoding。"""
    parsed = urllib.parse.urlparse(url)
    encoded = parsed._replace(
        path=urllib.parse.quote(parsed.path, safe="/%"),
        query=urllib.parse.quote(parsed.query, safe="=&%"),
    )
    return urllib.parse.urlunparse(encoded)


def _http_get(url, timeout=10):
    """发送 GET 请求并返回原始字节。

    统一附带 User-Agent，并限制单次响应体大小。
    """
    url = _encode_url(url)
    req = urllib.request.Request(url)
    req.add_header("User-Agent", USER_AGENT)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            content = response.read(MAX_RESPONSE_SIZE + 1)
    except Exception as e:
        raise RequestError(f"{e}")

    if len(content) > MAX_RESPONSE_SIZE:
        raise RequestError(f"响应超过 {MAX_RESPONSE_SIZE // 1024 // 1024}MB 限制")
    return content


def make_request(url, timeout=10):
    """请求 JSON 接口并返回解析后的对象。"""
    try:
        content = _http_get(url, timeout)
        return json.loads(content.decode("utf-8"))
    except RequestError:
        raise
    except Exception as e:
        raise RequestError(f"{e}")


def _load_local_config():
    """尝试从脚本同目录读取 config.json，不存在则返回 None。"""
    try:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        local_path = os.path.join(script_dir, "config.json")
        if os.path.isfile(local_path):
            with open(local_path, "r", encoding="utf-8") as f:
                return json.load(f)
    except Exception:
        pass
    return None


def load_config():
    """加载配置，并筛出可用的视频来源。

    优先从脚本同目录 config.json 读取，不存在则远程获取。
    """
    try:
        config = _load_local_config() or make_request(CONFIG_URL)
        api_site = config.get("api_site", {})
        api_name_map = {}
        for value in api_site.values():
            api = value.get("api", "")
            name = value.get("name", "")
            if api and "🎬" in name and "_comment" not in value:
                api_name_map[api] = name
        return api_name_map
    except Exception as e:
        raise ConfigError(f"加载配置失败: {e}")


def check_response(data, context=""):
    """检查 API 响应 code，非 1 时记录日志。返回 data 本身以便链式调用。"""
    code = data.get("code") if isinstance(data, dict) else None
    if code is not None and code != 1:
        msg = data.get("msg", "未知错误")
        _log("WARN", context, f"API 返回 code={code}, {msg}")
    return data


def ensure_api_sources(api_name_map):
    """确保配置中至少存在一个可用来源。"""
    if not api_name_map:
        raise ConfigError("未找到可用来源，请检查远程配置格式或过滤条件")


def resolve_m3u8_urls(url, context, timeout=10):
    """根据播放 URL 提取实际的 m3u8 地址列表。

    三种情况：
    1. URL 路径以 .m3u8 结尾 → 直接返回该 URL
    2. 请求后内容以 #EXTM3U 开头 → 该 URL 本身就是 m3u8
    3. 返回 HTML → 从页面中提取引号包裹的 .m3u8 路径并用 urljoin 拼接
    """
    parsed = urllib.parse.urlparse(url)
    if parsed.path.endswith(".m3u8"):
        return [url]

    try:
        content = _http_get(url, timeout).decode("utf-8", errors="replace")
    except Exception as e:
        _log("WARN", context, f"m3u8 探测失败 - {e}")
        return []

    stripped = content.lstrip()
    if stripped.startswith("#EXTM3U"):
        return [url]

    m3u8_paths = re.findall(r'["\']([^"\'\s]+\.m3u8)["\']', content)
    return [urllib.parse.urljoin(url, p) for p in m3u8_paths]


def clean_domain(url_or_domain):
    """提取纯域名，自动去掉协议、路径和端口。"""
    if "://" not in url_or_domain:
        url_or_domain = "http://" + url_or_domain
    return urllib.parse.urlparse(url_or_domain).hostname or url_or_domain


# ============== 播放地址解析 ==============


def parse_play_urls(raw):
    """解析播放/下载字段，返回全部 `(名称, URL)` 条目。"""
    results = []
    for group in raw.split("$$$"):
        for item in group.split("#"):
            if not item or "$" not in item:
                continue
            name, url = item.split("$", 1)
            results.append((name, url))
    return results


def parse_first_play_url_per_group(raw):
    """解析播放/下载字段，只保留每组第一条链接。

    Quantumult X 直连规则生成只需要每组取样一条链接即可。
    """
    results = []
    for group in raw.split("$$$"):
        for item in group.split("#"):
            if not item or "$" not in item:
                continue
            name, url = item.split("$", 1)
            results.append((name, url))
            break
    return results


# ============== 输出辅助 ==============


def print_quanx_result(api_domains, url_domains, m3u8_domains):
    print("; 资源站 API 域名")
    for domain in sorted(api_domains):
        print(f"host-suffix, {domain}, direct")

    print("; 播放/下载域名")
    for domain in sorted(url_domains):
        print(f"host-suffix, {domain}, direct")

    print("; m3u8 域名")
    for domain in sorted(m3u8_domains):
        print(f"host-suffix, {domain}, direct")


# ============== search 命令 ==============


def cmd_search(args, api_name_map):
    """并发搜索所有来源，输出 JSON 结果到 stdout。"""
    ensure_api_sources(api_name_map)
    keyword = args.keyword
    api_urls = list(api_name_map.keys())

    _log("INFO", "search", f"keyword={keyword}, sources={len(api_urls)}")

    def search_one(api_url):
        name = api_name_map.get(api_url, api_url)
        try:
            params = urllib.parse.urlencode(
                {"wd": keyword, "ac": "list", "pagesize": 100}
            )
            data = check_response(make_request(f"{api_url}?{params}"), name)
            return name, data.get("list") or []
        except Exception as e:
            _log("WARN", name, f"搜索失败 - {e}")
            return name, []

    max_workers = min(len(api_urls), 20)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        results = list(executor.map(search_one, api_urls))

    total = sum(len(vods) for _, vods in results)
    _log("INFO", "search", f"total_results={total}")

    output = {"keyword": keyword, "results": []}
    fields = ["vod_id", "vod_name", "vod_time", "vod_remarks"]
    for name, vods in results:
        for vod in vods:
            item = {"source": name}
            for field in fields:
                item[field] = vod.get(field, "")
            output["results"].append(item)

    print(json.dumps(output, ensure_ascii=False, indent=2))


# ============== fetch 命令 ==============


def cmd_fetch(args, api_name_map):
    """按来源名和 vod_id 获取播放/下载链接，输出 JSON 结果到 stdout。"""
    vod_id = args.vod_id
    source = args.source

    api_url = None
    for api, name in api_name_map.items():
        if name == source:
            api_url = api
            break

    if not api_url:
        available = ", ".join(sorted(set(api_name_map.values())))
        raise FetchError(f"未找到来源: {source}\n可用来源: {available}")

    try:
        params = urllib.parse.urlencode({"ids": vod_id, "ac": "detail"})
        data = check_response(make_request(f"{api_url}?{params}"), source)
        vod_list = data.get("list") or []

        if not vod_list:
            raise FetchError("未找到该视频")

        vod = vod_list[0]
        play_urls = parse_play_urls(vod.get("vod_play_url", ""))
        down_urls = parse_play_urls(vod.get("vod_down_url", ""))

        output = {
            "vod_id": vod_id,
            "source": source,
            "play_urls": [{"name": name, "url": url} for name, url in play_urls],
            "down_urls": [{"name": name, "url": url} for name, url in down_urls],
        }
        print(json.dumps(output, ensure_ascii=False, indent=2))
    except FetchError:
        raise
    except Exception as e:
        raise FetchError(f"获取失败: {e}")


# ============== quanx 命令 ==============


def fetch_m3u8_domains(m3u8_url, context, timeout=10, depth=0, cache=None, cache_lock=None):
    """递归提取 m3u8 清单中涉及的资源域名。

    - 主播放列表会继续跟进子清单
    - 普通清单直接提取媒体分片 URL
    - 递归深度最多 2 层
    - 可选共享缓存用于减少重复请求
    """
    if cache is None:
        cache = {}
    if cache_lock is not None:
        with cache_lock:
            cached = cache.get(m3u8_url)
    else:
        cached = cache.get(m3u8_url)
    if cached is not None:
        return cached

    domains = set()
    if depth > 2:
        if cache_lock is not None:
            with cache_lock:
                cache[m3u8_url] = domains
        else:
            cache[m3u8_url] = domains
        return domains
    try:
        content = _http_get(m3u8_url, timeout).decode("utf-8")
        lines = content.split("\n")

        has_stream_inf = any("#EXT-X-STREAM-INF" in line for line in lines)

        if has_stream_inf:
            sub_urls = []
            for i, line in enumerate(lines):
                if "#EXT-X-STREAM-INF" in line:
                    for j in range(i + 1, len(lines)):
                        stripped = lines[j].strip()
                        if not stripped:
                            continue
                        if stripped.startswith("#EXT-X-MEDIA") or stripped.startswith(
                            "#EXT-X-I-FRAME"
                        ):
                            continue
                        if not stripped.startswith("#"):
                            sub_urls.append(stripped)
                        break
            for sub_url in sub_urls[:3]:
                sub_url = urllib.parse.urljoin(m3u8_url, sub_url)
                sub_domain = clean_domain(sub_url)
                domains.add(sub_domain)
                sub_domains = fetch_m3u8_domains(
                    sub_url, context, timeout, depth + 1, cache, cache_lock
                )
                domains.update(sub_domains)
        else:
            for line in lines:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                full_url = urllib.parse.urljoin(m3u8_url, line)
                domain = clean_domain(full_url)
                domains.add(domain)
    except Exception as e:
        _log("WARN", context, f"m3u8 解析失败 - {e}")

    if cache_lock is not None:
        with cache_lock:
            cache[m3u8_url] = domains
    else:
        cache[m3u8_url] = domains
    return domains


def cmd_quanx(args, api_name_map):
    """收集 API、播放/下载、m3u8 三类域名并输出直连规则（纯文本）。"""
    ensure_api_sources(api_name_map)
    keyword = args.keyword
    api_urls = list(api_name_map.keys())

    _log("INFO", "quanx", f"keyword={keyword}, sources={len(api_urls)}")

    api_domains = set()
    url_domains = set()
    m3u8_domains = set()
    m3u8_cache = {}
    m3u8_cache_lock = Lock()

    for api_url in api_urls:
        domain = clean_domain(api_url)
        api_domains.add(domain)

    def process_api(api_url):
        name = api_name_map.get(api_url, api_url)
        local_url_domains = set()
        local_m3u8_domains = set()
        seen_m3u8_urls = set()
        try:
            params = urllib.parse.urlencode(
                {"wd": keyword, "ac": "list", "pagesize": 1}
            )
            data = check_response(make_request(f"{api_url}?{params}"), name)

            vod_list = data.get("list") or []
            if vod_list:
                vod = vod_list[0]
                vod_id = vod.get("vod_id")
                if vod_id:
                    params2 = urllib.parse.urlencode({"ids": vod_id, "ac": "detail"})
                    detail_data = check_response(
                        make_request(f"{api_url}?{params2}"), name
                    )

                    for v in detail_data.get("list") or []:
                        for field in ("vod_play_url", "vod_down_url"):
                            raw = v.get(field, "")
                            if not raw:
                                continue
                            for _, url in parse_first_play_url_per_group(raw):
                                domain = clean_domain(url)
                                local_url_domains.add(domain)
                                for m3u8_url in resolve_m3u8_urls(url, name):
                                    if m3u8_url in seen_m3u8_urls:
                                        continue
                                    seen_m3u8_urls.add(m3u8_url)
                                    m3u8 = fetch_m3u8_domains(
                                        m3u8_url, name,
                                        cache=m3u8_cache,
                                        cache_lock=m3u8_cache_lock,
                                    )
                                    local_m3u8_domains.update(m3u8)
        except Exception as e:
            _log("WARN", name, f"处理失败 - {e}")
        return local_url_domains, local_m3u8_domains

    max_workers = min(len(api_urls), 20)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(process_api, url): url for url in api_urls}
        for future in as_completed(futures):
            try:
                url_d, m3u8_d = future.result()
                url_domains.update(url_d)
                m3u8_domains.update(m3u8_d)
            except Exception as e:
                _log("WARN", "quanx", f"future 结果处理失败 - {e}")

    print_quanx_result(api_domains, url_domains, m3u8_domains)


# ============== logs 命令 ==============


def cmd_logs(args):
    """查看或清空日志文件。"""
    if args.clear:
        if os.path.isfile(LOG_FILE):
            open(LOG_FILE, "w").close()
        return
    if not os.path.isfile(LOG_FILE):
        print("日志文件为空")
        return
    with open(LOG_FILE, "r", encoding="utf-8") as f:
        lines = f.readlines()
    if not lines:
        print("日志文件为空")
        return
    if args.full:
        output = lines
    else:
        output = lines[-50:]
    for line in output:
        print(line, end="")


# ============== 通用退出处理 ==============


def exit_with_error(message):
    """记录错误日志并输出到 stderr，以非 0 状态退出。"""
    text = f"错误: {message}"
    _log("ERROR", "main", text)
    print(text, file=sys.stderr)
    raise SystemExit(1)


# ============== CLI 入口 ==============


def main():
    """解析命令行参数并分发到对应子命令。"""
    main_parser = argparse.ArgumentParser(
        description="小老虎爱看剧 (TigerTV)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
可用命令:
  search  <keyword>                        搜索视频
  fetch   --source <source> --vod_id <id>   获取视频详情
  quanx   <keyword>                        生成 Quantumult X 直连规则
  logs    [--full] [--clear]               查看日志

示例:
  tiger-tv.py search 逐玉
  tiger-tv.py fetch --source "🎬-爱奇艺-" --vod_id 73480
  tiger-tv.py quanx 逐玉
  tiger-tv.py logs
        """,
    )
    subparsers = main_parser.add_subparsers(dest="command", help="子命令")

    search_parser = subparsers.add_parser("search", help="搜索视频")
    search_parser.add_argument("keyword", help="搜索关键字")

    fetch_parser = subparsers.add_parser("fetch", help="获取视频详情")
    fetch_parser.add_argument("--source", required=True, help="来源名称")
    fetch_parser.add_argument("--vod_id", required=True, type=int, help="视频 ID")

    quanx_parser = subparsers.add_parser("quanx", help="生成 Quantumult X 直连规则")
    quanx_parser.add_argument("keyword", help="搜索关键字")

    logs_parser = subparsers.add_parser("logs", help="查看日志")
    logs_parser.add_argument("--full", action="store_true", help="显示全部日志")
    logs_parser.add_argument("--clear", action="store_true", help="清空日志文件")

    args = main_parser.parse_args()

    if not args.command:
        main_parser.print_help()
        raise SystemExit(0)

    try:
        api_name_map = load_config()

        if args.command == "search":
            cmd_search(args, api_name_map)
        elif args.command == "fetch":
            cmd_fetch(args, api_name_map)
        elif args.command == "quanx":
            cmd_quanx(args, api_name_map)
        elif args.command == "logs":
            cmd_logs(args)
    except (ConfigError, FetchError, RequestError) as e:
        exit_with_error(e)


if __name__ == "__main__":
    main()
