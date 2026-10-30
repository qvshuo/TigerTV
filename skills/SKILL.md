---
name: TigerTV
description: "当任务涉及影视搜索、按站点和 vod_id 获取播放或下载链接、下载剧集，或为影视资源站域名生成 Quantumult X 直连规则时，优先使用此技能。"
triggers:
  - 影视搜索
  - 剧集下载
  - 播放/下载链接
  - Quantumult X
  - 直连规则
  - 代理规则
  - 小老虎爱看剧
---

# TigerTV：小老虎爱看剧.skill

用于搜索影视剧集、获取播放/下载链接、下载剧集，以及生成 Quantumult X 直连规则。

## 安装

首次使用，或当前环境中不存在 `tigertv` 命令时：

```bash
curl -fsSL https://raw.githubusercontent.com/qvshuo/TigerTV/main/skills/scripts/install.sh | bash
```

CLI 提供 macOS arm64 Kotlin/Native 二进制，命令名为 `tigertv`，运行无需 Python 或 Java。

可通过 `TIGERTV_VERSION` 固定版本、`TIGERTV_INSTALL_DIR` 自定义安装目录。安装程序验证发布包 SHA-256，不自动修改 PATH。

搜索、获取链接和生成规则只依赖 `tigertv`；下载剧集时，还需要 `yt-dlp` 和 `ffmpeg`。

## 全局参数

除 `logs` 外，所有子命令均可使用：

```bash
tigertv --config <path> search --query <keyword>
```

`--config` 用于指定本地 JSON 配置文件（格式与远程配置一致），会完全跳过远程配置加载和本地缓存。

## 使用

### 1. 搜索

```bash
tigertv search --query <keyword>
```

日志和诊断信息不写入 stdout；搜索结果以 JSON 输出到 stdout。示例输出：

```json
{
  "query": "逐玉",
  "works": [
    {
      "id": "[\"逐玉\",\"2026\",\"tv\"]",
      "title": "逐玉",
      "sources": [
        {"site": "🎬金鹰点播", "vod_id": 104571, "vod_name": "逐玉", "vod_time": "2026-03-22", "vod_remarks": "全40集", "vod_pic": "", "vod_year": "2026", "type_name": "国产剧"}
      ]
    }
  ]
}
```

### 2. 获取链接

命令示例：

```bash
tigertv fetch --site "🎬金鹰点播" --video-id 104571
```

`--site` 必须完整匹配站点名称（包括 emoji 和标点），`--video-id` 为整数；二者均可从 `search` 的 `works[].sources[]` 中获取。若站点名不匹配，CLI 会报错并列出可用站点名，可据此修正。

### 3. 下载

**默认行为**：下载根目录为 `~/Downloads/小老虎爱看剧`，目录结构为 `剧名/Season N/SxxExx.mp4`。未指定季号时使用 `Season 1`；未指定资源来源时使用第一组可用链接；未指定集数时下载全部集数。

**链接选择**：`fetch` 返回 `play_sources` 和 `download_sources`，每条线路有 `id`、`name` 与 `episodes`（含 `name`、`url`）。优先从 `download_sources` 选择一条可用线路，为空时使用 `play_sources`。只下载选定线路的集数，不混合多条线路。

**必要参数**：调用 `yt-dlp` 下载时，应携带以下参数，以支持重试并提高下载稳定性：

```
--retries 20 --retry-sleep 5 \
--concurrent-fragments 16 --fragment-retries 20 \
--socket-timeout 30 --ignore-errors
```

命令示例：

```bash
mkdir -p ~/Downloads/小老虎爱看剧/逐玉/Season\ 1
yt-dlp \
  --retries 20 --retry-sleep 5 \
  --concurrent-fragments 16 --fragment-retries 20 \
  --socket-timeout 30 --ignore-errors \
  -o "~/Downloads/小老虎爱看剧/逐玉/Season 1/S01E01.mp4" "https://example.com/ep01.m3u8"
```

### 4. 生成 Quantumult X 规则

```bash
tigertv quanx --query <keyword>
```

输出分为三段：资源站 API 域名、播放/下载域名、m3u8 及相关 CDN 域名。

### 5. 查看日志

```bash
tigertv logs
```

默认显示最近 50 行。使用 `--full` 查看全部日志，使用 `--clear` 清空日志文件。

## 典型流程

```text
1. 搜索 -> 获取链接 -> 使用 yt-dlp 下载
tigertv search --query <keyword> -> tigertv fetch --site "..." --video-id ... -> yt-dlp ...
2. 生成 Quantumult X 直连规则
tigertv quanx --query <keyword>
```
