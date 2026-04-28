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
MAX_RESPONSE_SIZE = 10 * 1024 * 1024  # 10MB：防止异常大响应耗尽内存
LOG_FILE = "/tmp/tiger-tv.log"
LOG_MAX_LINES = 5000  # 超过则截断，防止长期运行后日志无限膨胀
LOG_KEEP_LINES = 2000  # 截断后保留最近 N 行
CACHE_FILE = "/tmp/tiger-tv-config-cache.json"
CACHE_TTL = 86400  # 1 天：远程配置变动不频繁，过长会滞后

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
    """线程安全地写入结构化日志。

    格式：YYYY-MM-DD HH:MM:SS [LEVEL] [site_name]: message
    所有输出到 stdout 的命令均不应混入日志，避免破坏 JSON/纯文本管道解析。
    """
    timestamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"{timestamp} [{level}] [{context}]: {message}\n"
    with _log_lock:
        if os.path.isfile(LOG_FILE):
            try:
                with open(LOG_FILE, "r", encoding="utf-8") as f:
                    lines = f.readlines()
                if len(lines) > LOG_MAX_LINES:
                    lines = lines[-LOG_KEEP_LINES:]
                    with open(LOG_FILE, "w", encoding="utf-8") as f:
                        f.writelines(lines)
            except Exception:
                pass
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line)


def _encode_url(url):
    """percent-encode 非 ASCII 字符，避免 urllib 抛出 UnicodeEncodeError。

    safe 保留 `/` 和 `%` 是因为路径中已有的 percent-encoding 不应被二次编码。
    """
    parsed = urllib.parse.urlparse(url)
    encoded = parsed._replace(
        path=urllib.parse.quote(parsed.path, safe="/%"),
        query=urllib.parse.quote(parsed.query, safe="=&%"),
    )
    return urllib.parse.urlunparse(encoded)


def _http_get(url, timeout=10):
    """发送 GET 请求，返回原始字节。

    统一附带 User-Agent：部分资源站对无 UA 请求直接拒绝。
    用 MAX_RESPONSE_SIZE + 1 探测超限：避免流式读取的复杂性。
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
    """请求 JSON 接口并返回 dict。

    返回类型校验：MacCMS API 的 code/msg/info 结构要求顶层必须是对象。
    """
    try:
        content = _http_get(url, timeout)
        data = json.loads(content.decode("utf-8"))
    except RequestError:
        raise
    except Exception as e:
        raise RequestError(f"{e}")
    if not isinstance(data, dict):
        raise RequestError(f"API 返回非对象类型: {type(data).__name__}")
    return data


def _parse_config(config):
    """将原始配置 JSON 解析为 {api_url: site_name} 映射。

    过滤逻辑：只保留名称含 🎬 且无 _comment 的站点，_comment 用于标记备用/失效源。
    """
    api_site = config.get("api_site", {})
    api_name_map = {}
    for value in api_site.values():
        api = value.get("api", "")
        name = value.get("name", "")
        if api and "🎬" in name and "_comment" not in value:
            api_name_map[api] = name
    if not api_name_map:
        raise ConfigError("未找到可用站点，请检查配置格式或过滤条件")
    return api_name_map


def _load_cached_config(check_ttl=True):
    """读取本地缓存。check_ttl=False 时忽略过期时间，用于远程失败后的降级。"""
    try:
        if not os.path.isfile(CACHE_FILE):
            return None
        with open(CACHE_FILE, "r", encoding="utf-8") as f:
            cached = json.load(f)
        if check_ttl:
            fetched_at = datetime.datetime.fromisoformat(cached.get("fetched_at", ""))
            if (datetime.datetime.now() - fetched_at).total_seconds() > CACHE_TTL:
                return None
        return cached.get("config")
    except Exception:
        return None


def _save_config_cache(config):
    """将配置写入缓存文件。失败静默处理：不影响主流程。"""
    try:
        payload = {
            "fetched_at": datetime.datetime.now().isoformat(),
            "config": config,
        }
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def load_config(source=None):
    """加载站点配置。

    优先级：
    1. --source 指定的本地配置文件（完全隔离，不读写缓存）
    2. 未过期的本地缓存
    3. 远程 CONFIG_URL（成功后写入缓存）
    4. 远程失败时降级使用过期缓存
    """
    if source:
        try:
            with open(source, "r", encoding="utf-8") as f:
                config = json.load(f)
            return _parse_config(config)
        except Exception as e:
            raise ConfigError(f"读取 source 失败: {e}")

    cached = _load_cached_config(check_ttl=True)
    if cached is not None:
        return _parse_config(cached)

    try:
        config = make_request(CONFIG_URL)
        _save_config_cache(config)
        return _parse_config(config)
    except Exception as e:
        stale = _load_cached_config(check_ttl=False)
        if stale is not None:
            _log("WARN", "config", f"远程配置获取失败，使用过期缓存: {e}")
            return _parse_config(stale)
        raise ConfigError(f"加载配置失败: {e}")


def check_response(data, context=""):
    """校验 API code 并返回 data 本身，支持链式调用。"""
    code = data.get("code") if isinstance(data, dict) else None
    if code is not None and code != 1:
        msg = data.get("msg", "未知错误")
        _log("WARN", context, f"API 返回 code={code}, {msg}")
    return data


def ensure_api_sites(api_name_map):
    """配置加载后校验：空配置直接失败，避免后续无意义请求。"""
    if not api_name_map:
        raise ConfigError("未找到可用站点，请检查远程配置格式或过滤条件")


def resolve_m3u8_urls(url, context, timeout=10):
    """将播放页 URL 解析为实际 m3u8 地址。

    资源站返回的 play_url 通常是跳转页（HTML）而非直接 m3u8。
    需要探测内容类型：直接 m3u8 / HTML 中的引用路径 / 跳转页本身。
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

    # 匹配引号中的 .m3u8 路径（支持 ?sign=... 查询参数）
    m3u8_paths = re.findall(r'["\']([^"\'\s]+\.m3u8[^"\'\s]*)["\']', content)
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
    """并发搜索所有站点，单站失败不影响整体结果。"""
    ensure_api_sites(api_name_map)
    keyword = args.keyword
    api_urls = list(api_name_map.keys())

    _log("INFO", "search", f"keyword={keyword}, sites={len(api_urls)}")

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
            item = {"site": name}
            for field in fields:
                item[field] = vod.get(field, "")
            output["results"].append(item)

    print(json.dumps(output, ensure_ascii=False, indent=2))


# ============== fetch 命令 ==============


def cmd_fetch(args, api_name_map):
    """按站点名和 vod_id 获取详情，输出 JSON。"""
    vod_id = args.vod_id
    site = args.site

    api_url = None
    for api, name in api_name_map.items():
        if name == site:
            api_url = api
            break

    if not api_url:
        available = ", ".join(sorted(set(api_name_map.values())))
        raise FetchError(f"未找到站点: {site}\n可用站点: {available}")

    try:
        params = urllib.parse.urlencode({"ids": vod_id, "ac": "detail"})
        data = check_response(make_request(f"{api_url}?{params}"), site)
        vod_list = data.get("list") or []

        if not vod_list:
            raise FetchError("未找到该视频")

        vod = vod_list[0]
        play_urls = parse_play_urls(vod.get("vod_play_url", ""))
        down_urls = parse_play_urls(vod.get("vod_down_url", ""))

        output = {
            "vod_id": vod_id,
            "site": site,
            "vod_play_url": [{"name": name, "url": url} for name, url in play_urls],
            "vod_down_url": [{"name": name, "url": url} for name, url in down_urls],
        }
        print(json.dumps(output, ensure_ascii=False, indent=2))
    except FetchError:
        raise
    except Exception as e:
        raise FetchError(f"获取失败: {e}")


# ============== quanx 命令 ==============


def fetch_m3u8_domains(m3u8_url, context, timeout=10, depth=0, cache=None, cache_lock=None):
    """递归解析 m3u8 清单，提取涉及的 CDN 域名。

    主播放列表可能包含多码率子清单（#EXT-X-STREAM-INF），需要递归跟进。
    深度限制为 2：避免嵌套过深导致请求风暴；缓存避免并发时重复请求同一 m3u8。
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
        content = _http_get(m3u8_url, timeout).decode("utf-8", errors="replace")
        lines = content.split("\n")

        has_stream_inf = any("#EXT-X-STREAM-INF" in line for line in lines)

        if has_stream_inf:
            sub_urls = []
            for i, line in enumerate(lines):
                if "#EXT-X-STREAM-INF" in line:
                    for j in range(i + 1, len(lines)):
                        stripped = lines[j].strip()
                        if not stripped or stripped.startswith("#"):
                            continue
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
    """搜索并输出 Quantumult X 直连规则（纯文本）。"""
    ensure_api_sites(api_name_map)
    keyword = args.keyword
    api_urls = list(api_name_map.keys())

    _log("INFO", "quanx", f"keyword={keyword}, sites={len(api_urls)}")

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
                                if not url:
                                    continue
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
    """查看或清空日志文件。日志与 stdout 分离，避免污染命令输出。"""
    if args.clear:
        if os.path.isfile(LOG_FILE):
            open(LOG_FILE, "w").close()
        print("日志已清空")
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
    """统一错误出口：记录日志 + stderr 输出 + 非零退出码。"""
    text = f"错误: {message}"
    _log("ERROR", "main", text)
    print(text, file=sys.stderr)
    raise SystemExit(1)


# ============== CLI 入口 ==============


def main():
    """命令分发入口。"""
    main_parser = argparse.ArgumentParser(
        description="小老虎爱看剧 (TigerTV)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
可用命令:
  search  <keyword>                        搜索视频
  fetch   --site <site> --vod_id <id>       获取视频详情
  quanx   <keyword>                        生成 Quantumult X 直连规则
  logs    [--full] [--clear]               查看日志

示例:
  tiger-tv.py search 逐玉
  tiger-tv.py --source ./my-sources.json search 逐玉
  tiger-tv.py fetch --site "🎬-爱奇艺-" --vod_id 73480
  tiger-tv.py quanx 逐玉
  tiger-tv.py logs
        """,
    )
    main_parser.add_argument(
        "--source",
        help="指定来源配置文件路径（JSON 格式），优先级高于缓存和远程配置",
    )
    subparsers = main_parser.add_subparsers(dest="command", help="子命令")

    search_parser = subparsers.add_parser("search", help="搜索视频")
    search_parser.add_argument("keyword", help="搜索关键字")

    fetch_parser = subparsers.add_parser("fetch", help="获取视频详情")
    fetch_parser.add_argument("--site", required=True, help="站点名称")
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
        api_name_map = load_config(source=args.source)

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
