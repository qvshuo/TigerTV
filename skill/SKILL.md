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

首次使用，或当前环境中不存在 `tigertv-cli.py` 命令时：

```bash
curl -fsSL https://raw.githubusercontent.com/qvshuo/TigerTV/main/skill/scripts/install.sh | bash
```

搜索、获取链接和生成规则只依赖 `tigertv-cli.py`；下载剧集时，还需要 `yt-dlp` 和 `ffmpeg`。

## 全局参数

除 `logs` 外，所有子命令均可使用：

```bash
tigertv-cli.py --source <path> search <keyword>
```

`--source` 用于指定本地 JSON 配置文件（格式与远程配置一致），会完全跳过远程配置加载和本地缓存。

## 使用

### 1. 搜索

```bash
tigertv-cli.py search <keyword>
```

日志和诊断信息不写入 stdout；搜索结果以 JSON 输出到 stdout。示例输出：

```json
{
  "keyword": "逐玉",
  "results": [
    {
      "site": "🎬金鹰点播",
      "vod_id": 104571,
      "vod_name": "逐玉",
      "vod_time": "2026-03-22 23:35:02",
      "vod_remarks": "第40集已完结"
    }
  ]
}
```

### 2. 获取链接

命令示例：

```bash
tigertv-cli.py fetch --site "🎬金鹰点播" --vod_id 104571
```

`--site` 必须完整匹配站点名称（包括 emoji 和标点），`--vod_id` 为整数；二者均可从 `search` 输出中获取。若站点名不匹配，脚本会报错并列出可用站点名，可据此修正。

### 3. 下载

**默认行为**：下载根目录为 `~/Downloads/小老虎爱看剧`，目录结构为 `剧名/Season N/SxxExx.mp4`。未指定季号时使用 `Season 1`；未指定资源来源时使用第一组可用链接；未指定集数时下载全部集数。

**链接选择**：`fetch` 输出可能包含多组链接，例如第一组为 `mp4`，第二组为 `.m3u8`。默认优先使用第一组；若第一组不可用，再按顺序尝试后续分组。

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
tigertv-cli.py quanx <keyword>
```

输出分为三段：资源站 API 域名、播放/下载域名、m3u8 及相关 CDN 域名。

### 5. 查看日志

```bash
tigertv-cli.py logs
```

默认显示最近 50 行。使用 `--full` 查看全部日志，使用 `--clear` 清空日志文件。

## 典型流程

```text
1. 搜索 -> 获取链接 -> 使用 yt-dlp 下载
tigertv-cli.py search <keyword> -> tigertv-cli.py fetch --site "..." --vod_id ... -> yt-dlp ...
2. 生成 Quantumult X 直连规则
tigertv-cli.py quanx <keyword>
```
