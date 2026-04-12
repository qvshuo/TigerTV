#!/usr/bin/env python3
"""
小老虎爱看剧 (TigerTV)
命令行入口：tiger-tv.py <command> [options]
"""

import argparse
import json
import sys
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

# ============== 基础配置 ==============

CONFIG_URL = "https://raw.githubusercontent.com/hafrey1/LunaTV-config/refs/heads/main/LunaTV-config.json"
USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 Safari/605.1.15"
MAX_RESPONSE_SIZE = 10 * 1024 * 1024  # 10MB


# ============== 异常类型 ==============


class ConfigError(Exception):
    pass


class FetchError(Exception):
    pass


class RequestError(Exception):
    pass


# ============== 请求与基础工具 ==============


def _http_get(url, timeout=10):
    """发送 GET 请求并返回原始字节。

    统一附带 User-Agent，并限制单次响应体大小。
    """
    req = urllib.request.Request(url)
    req.add_header("User-Agent", USER_AGENT)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            content = response.read(MAX_RESPONSE_SIZE + 1)
    except Exception as e:
        raise RequestError(f"请求失败: {e}")

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
        raise RequestError(f"响应解析失败: {e}")


def load_config():
    """加载远程配置，并筛出可用的视频来源。"""
    try:
        config = make_request(CONFIG_URL)
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


def ensure_api_sources(api_name_map):
    """确保配置中至少存在一个可用来源。"""
    if not api_name_map:
        raise ConfigError("未找到可用来源，请检查远程配置格式或过滤条件")


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


def print_search_progress(keyword, api_count):
    print(f"搜索关键字: {keyword}", file=sys.stderr)
    print(f"API 数量: {api_count}", file=sys.stderr)


def print_search_result(results):
    total = sum(len(vods) for _, vods in results)
    print(f"总记录数: {total}", file=sys.stderr)
    print(file=sys.stderr)

    fields = ["vod_id", "vod_name", "vod_time", "vod_remarks"]
    for name, vods in results:
        if not vods:
            continue
        for vod in vods:
            print(f"{name}:")
            for field in fields:
                value = vod.get(field, "")
                print(f"  {field}: {value}")
            print()

    if total == 0:
        print("未找到任何结果")


def print_fetch_result(vod_id, source, play_urls, down_urls):
    print(f"视频ID: {vod_id}")
    print(f"来源: {source}")
    print("-" * 50)

    if play_urls:
        print("【播放链接】")
        for name, url in play_urls:
            print(f"{name}：{url}")

    if down_urls:
        print("【下载链接】")
        for name, url in down_urls:
            print(f"{name}：{url}")


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


def print_warning(context, message):
    print(f"警告 [{context}]: {message}", file=sys.stderr)


# ============== search 命令 ==============


def cmd_search(args, api_name_map):
    """并发搜索所有来源，输出匹配到的视频列表。"""
    ensure_api_sources(api_name_map)
    keyword = args.keyword
    api_urls = list(api_name_map.keys())

    print_search_progress(keyword, len(api_urls))

    def search_one(api_url):
        name = api_name_map.get(api_url, api_url)
        try:
            params = urllib.parse.urlencode(
                {"wd": keyword, "ac": "list", "pagesize": 100}
            )
            data = make_request(f"{api_url}?{params}")
            return name, data.get("list", [])
        except Exception as e:
            print_warning(name, f"搜索失败: {e}")
            return name, []

    max_workers = min(len(api_urls), 20)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        results = list(executor.map(search_one, api_urls))

    print_search_result(results)


# ============== fetch 命令 ==============


def cmd_fetch(args, api_name_map):
    """按来源名和 vod_id 获取播放/下载链接。"""
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
        data = make_request(f"{api_url}?{params}")
        vod_list = data.get("list", [])

        if not vod_list:
            raise FetchError("未找到该视频")

        vod = vod_list[0]
        play_urls = parse_play_urls(vod.get("vod_play_url", ""))
        down_urls = parse_play_urls(vod.get("vod_down_url", ""))
        print_fetch_result(vod_id, source, play_urls, down_urls)
    except FetchError:
        raise
    except Exception as e:
        raise FetchError(f"获取失败: {e}")


# ============== quanx 命令 ==============


def fetch_m3u8_domains(m3u8_url, timeout=10, depth=0, cache=None, cache_lock=None):
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
                    sub_url, timeout, depth + 1, cache, cache_lock
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
        print_warning("m3u8", f"解析失败: {e}")

    if cache_lock is not None:
        with cache_lock:
            cache[m3u8_url] = domains
    else:
        cache[m3u8_url] = domains
    return domains


def cmd_quanx(args, api_name_map):
    """收集 API、播放/下载、m3u8 三类域名并输出直连规则。"""
    ensure_api_sources(api_name_map)
    keyword = args.keyword
    api_urls = list(api_name_map.keys())

    api_domains = set()
    url_domains = set()
    m3u8_domains = set()
    m3u8_cache = {}
    m3u8_cache_lock = Lock()

    for api_url in api_urls:
        domain = clean_domain(api_url)
        api_domains.add(domain)

    def process_api(api_url):
        local_url_domains = set()
        local_m3u8_domains = set()
        seen_m3u8_urls = set()
        try:
            params = urllib.parse.urlencode(
                {"wd": keyword, "ac": "list", "pagesize": 1}
            )
            data = make_request(f"{api_url}?{params}")

            vod_list = data.get("list", [])
            if vod_list:
                vod = vod_list[0]
                vod_id = vod.get("vod_id")
                if vod_id:
                    params2 = urllib.parse.urlencode({"ids": vod_id, "ac": "detail"})
                    detail_data = make_request(f"{api_url}?{params2}")

                    for v in detail_data.get("list", []):
                        for field in ("vod_play_url", "vod_down_url"):
                            raw = v.get(field, "")
                            if not raw:
                                continue
                            for _, url in parse_first_play_url_per_group(raw):
                                domain = clean_domain(url)
                                local_url_domains.add(domain)
                                parsed = urllib.parse.urlparse(url)
                                if (
                                    parsed.path.endswith(".m3u8")
                                    and url not in seen_m3u8_urls
                                ):
                                    seen_m3u8_urls.add(url)
                                    m3u8 = fetch_m3u8_domains(
                                        url,
                                        cache=m3u8_cache,
                                        cache_lock=m3u8_cache_lock,
                                    )
                                    local_m3u8_domains.update(m3u8)
        except Exception as e:
            print_warning(clean_domain(api_url), f"处理失败: {e}")
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
                print_warning("future", f"处理失败: {e}")

    print_quanx_result(api_domains, url_domains, m3u8_domains)


# ============== 通用退出处理 ==============


def exit_with_error(message):
    """输出统一错误信息并以非 0 状态退出。"""
    print(f"错误: {message}", file=sys.stderr)
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

示例:
  tiger-tv.py search 逐玉
  tiger-tv.py fetch --source "🎬-爱奇艺-" --vod_id 73480
  tiger-tv.py quanx 逐玉
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
    except (ConfigError, FetchError, RequestError) as e:
        exit_with_error(e)


if __name__ == "__main__":
    main()
