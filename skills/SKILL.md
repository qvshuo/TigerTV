---
name: tigertv
description: 使用 TigerTV 搜索影视、获取指定资源的播放和下载链接、下载剧集，或生成 Quantumult X 直连规则。适用于 macOS arm64 命令行工作流。
---

# TigerTV

`tigertv` 是共享 Kotlin 核心的原生命令行入口。搜索、聚合、MacCMS 解析与域名收集均由核心完成；下载交给 `yt-dlp` 和 `ffmpeg`。

## 准备

先检查 `command -v tigertv` 和 `tigertv --version`。缺失时使用随技能提供的安装器：

```bash
bash <技能目录>/scripts/install.sh
```

安装器仅支持 macOS arm64，验证发布包 SHA-256，默认安装到 `~/.local/bin`。`TIGERTV_VERSION` 可指定版本，`TIGERTV_INSTALL_DIR` 可指定目录；不会修改 PATH。安装后命令不在 PATH 时，直接使用安装路径。更新时重新运行安装器；卸载使用同目录的 `uninstall.sh`，保留缓存与日志。

## 搜索与详情

```bash
tigertv search --query '嫁金钗'
tigertv fetch --site '🎬站点完整名' --video-id 42
```

搜索输出 `{query, works}`。每个作品包含 `id`、`title`、`sources`；从 `sources[]` 读取完整的 `site` 和正整数 `vod_id`，分别传给 `--site` 和 `--video-id`。作品 `id` 是不透明聚合标识，不能当作资源内容 ID。相同标题与年份会合并，可能存在误聚合；选择资源时保留用户指定的版本、年份和集数要求。

所有配置资源并发搜索，CLI 等待完成后输出一次 JSON。空 `works` 表示没有匹配结果；部分资源失败会记录日志，其他资源继续返回。资源顺序沿用配置顺序，不代表清晰度或可用性排名。

详情包含 `play_sources` 和 `download_sources`，两者均为线路数组；每条线路含 `id`、`name`、`episodes: [{name,url}]`。线路和选集可能为空；只使用实际返回的链接。资源的 `vod_id`、线路与选集只在所属资源内有效，切换资源后重新获取详情。

本地配置放在命令之前，并在同一流程的搜索、详情或规则命令中保持一致：

```bash
tigertv --config '/path/to/sources.json' search --query '嫁金钗'
tigertv --config '/path/to/sources.json' fetch --site '🎬站点完整名' --video-id 42
```

配置结构为 `{api_site: {key: {name, api}}}`；资源名称需含 `🎬`，设置了 `_comment` 的条目会被排除，相同 API URL 只保留首项。本地文件绕过远程配置与配置缓存。搜索结果不使用 CLI 缓存。

## 下载

先确认用户需要的资源、线路和集数；用户未指定时，优先使用第一条非空下载线路，否则使用第一条非空播放线路，并下载该线路全部选集。不要混合不同线路的集数。

默认保存到 `~/Downloads/小老虎爱看剧/剧名/Season 1/`，用户指定位置或季号时遵循其要求。文件名使用 `S01E01` 等编号，编号按该线路的选集顺序生成；特殊篇名称另行保留。将标题中的路径分隔符去除，参数通过独立参数数组传递或正确引用，避免直接拼接 shell 命令。

例如已选择某条真实返回的 HLS 链接后：

```bash
mkdir -p "$HOME/Downloads/小老虎爱看剧/嫁金钗/Season 1"
yt-dlp --retries 10 --fragment-retries 10 \
  --merge-output-format mp4 --remux-video mp4 \
  -o "$HOME/Downloads/小老虎爱看剧/嫁金钗/Season 1/S01E01.%(ext)s" \
  '实际选集链接'
```

批量下载后核对退出状态和实际文件，报告失败的选集。命令执行成功或链接已获取不等于下载已完成；不要隐藏失败。链接可能需要解析或特定请求头，403 也可能是鉴权、过期或访问限制，先检查响应与日志。

## Quantumult X 与诊断

```bash
tigertv quanx --query '嫁金钗'
tigertv logs
tigertv logs --full
tigertv logs --clear
tigertv search --help
```

`quanx` 输出资源站 API、播放/下载以及 HLS 相关域名的规则文本；按请求交付文本或文件，写入已有代理配置须有用户授权。

`search` / `fetch` 的 stdout 为 JSON，`quanx` / `logs` 为文本；stderr 为错误。退出码：成功、帮助及下游关闭管道为 0，业务错误为 1，参数错误为 2。默认日志最近 50 行；`--clear` 会删除诊断记录，仅在需要清空时使用。`logs` 不使用 `--config`。
