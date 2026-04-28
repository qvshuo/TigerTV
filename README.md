# 小老虎爱看剧 (TigerTV)

基于资源站 API 的影视搜索、播放/下载链接提取与 Quantumult X 直连规则生成工具。

当前仓库仅包含一个核心入口：
- `tiger-tv.py`：可直接运行的 Python CLI，负责搜索、链接提取和规则生成

## 安装

可以直接在仓库中运行 `tiger-tv.py`；如需作为全局命令使用，可执行安装脚本将其安装到 `~/.local/bin/tiger-tv.py`。安装脚本不会修改环境变量，可按下面方式一并完成安装和 `PATH` 配置：

```bash
# 安装 tiger-tv.py 到 ~/.local/bin
curl -fsSL https://raw.githubusercontent.com/qvshuo/TigerTV/main/install.sh | bash

# bash 用户
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

# zsh 用户
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

## 当前能力

- 通过多个资源站 API 搜索剧集
- 按来源名和 `vod_id` 获取播放/下载链接
- 提取 API 域名、播放/下载域名、m3u8 域名并生成 Quantumult X `host-suffix` 直连规则
- 对运行时请求失败和来源异常提供一致的错误/警告输出

## 运行要求

- Python 3
- 安装后命令位于 `~/.local/bin/tiger-tv.py`
- `tiger-tv.py` 仅使用标准库，无第三方依赖
- 运行时需要能访问 GitHub RAW 和目标资源站 API

## 仓库结构

```text
tiger-tv/
├── install.sh
├── tiger-tv.py
├── uninstall.sh
├── README.md
├── LICENSE
└── API接口说明V2.txt
```

## tiger-tv.py 命令

```bash
# 查看帮助
tiger-tv.py --help

# 搜索视频（JSON 输出）
tiger-tv.py search 逐玉

# 获取播放/下载链接（JSON 输出，source 需完整匹配）
tiger-tv.py fetch --source "🎬-爱奇艺-" --vod_id 73480

# 生成 Quantumult X 直连规则（纯文本输出）
tiger-tv.py quanx 逐玉

# 使用自定义来源列表（跳过远程配置和缓存）
tiger-tv.py --source-list ./my-sources.json search 逐玉

# 查看最近日志
tiger-tv.py logs

# 查看全部日志
tiger-tv.py logs --full

# 清空日志
tiger-tv.py logs --clear
```

## 命令说明

| 命令 | 参数 | 说明 |
|---|---|---|
| `search` | `<keyword>` | 搜索关键字，单源最多取 100 条，输出 JSON |
| `fetch` | `--source <source> --vod_id <id>` | 精准匹配来源并获取播放/下载链接，输出 JSON |
| `quanx` | `<keyword>` | 搜索并提取域名，输出 Quantumult X 直连规则（纯文本） |
| `logs` | `[--full] [--clear]` | 查看最近 50 条日志，`--full` 显示全部，`--clear` 清空 |

## 输出约定

- **`search` / `fetch`**：结果以 JSON 输出到 `stdout`，可直接被管道或脚本解析。
- **`quanx`**：输出 Quantumult X 规则纯文本，可直接复制到配置中使用。
- **日志**：所有运行日志（INFO/WARN/ERROR）写入 `/tmp/tiger-tv.log`，通过 `logs` 命令查看，不再混入 `stdout`/`stderr`。

### search JSON 示例

```json
{
  "keyword": "逐玉",
  "results": [
    {"source": "🎬-爱奇艺-", "vod_id": 73480, "vod_name": "逐玉", "vod_time": "...", "vod_remarks": "全40集"}
  ]
}
```

### fetch JSON 示例

```json
{
  "vod_id": 73480,
  "source": "🎬-爱奇艺-",
  "play_urls": [{"name": "第01集", "url": "https://..."}],
  "down_urls": []
}
```

### quanx 纯文本格式

```text
; 资源站 API 域名
host-suffix, example.com, direct
; 播放/下载域名
host-suffix, example.com, direct
; m3u8 域名
host-suffix, example.com, direct
```

## 实现要点

- 来源配置默认从 `CONFIG_URL`（GitHub RAW）远程加载，加载结果会写入本地缓存（`/tmp/tiger-tv-config-cache.json`），缓存有效期 1 天。远程获取失败且存在缓存时，会自动降级使用缓存（即使已过期）。
- 可通过 `--source-list <path>` 指定本地 JSON 配置文件，此时完全跳过远程和缓存逻辑；文件格式与 LunaTV-config.json 一致。只保留名称包含 `🎬` 且不含 `_comment` 的来源。
- 所有请求统一经过 `_http_get()` / `make_request()`，默认超时 10 秒，单次响应体限制 10MB；URL 中的非 ASCII 字符自动 percent-encoding。
- `search` 最多并发请求 20 个来源；`fetch` 通过 `ac=detail` 和 `ids=<vod_id>` 获取详情。
- API 响应会检查 `code` 字段，非 1 时记录日志。
- `quanx` 会收集 API 域名、播放/下载域名，以及最多递归 2 层解析得到的 m3u8 资源域名。对于路径不以 `.m3u8` 结尾的播放链接，会请求内容探测实际 m3u8 地址（直接 m3u8 响应或从 HTML 页面中提取）。
- 日志统一写入 `/tmp/tiger-tv.log`，格式为 `时间戳 [级别] [来源]: 消息`，所有上下文统一使用来源名。

## 来源名称说明

`fetch --source` 需使用配置中的完整 `name`，例如：

- `🎬-爱奇艺-`
- `🎬红牛资源`
- `🎬飘零资源`

如果不知道来源名，可以故意传错：

```bash
tiger-tv.py fetch --source test --vod_id 1
```

程序会在错误信息中列出可用来源。

## 常见问题

**Q: 搜索结果为 0？**  
A: 先检查网络是否可访问 GitHub RAW 和目标资源站 API；部分来源可能临时失效。

**Q: `fetch` 提示“未找到来源”？**  
A: `--source` 必须完整匹配配置中的 `name` 字段，包括其中的 emoji 或分隔符。

**Q: `--vod_id` 传错类型会怎样？**  
A: 参数由 `argparse` 校验，非整数会直接报参数错误。

**Q: `quanx` 输出里 m3u8 域名为空？**  
A: 说明未解析到额外的 m3u8 资源域名，或对应请求超时、返回异常。

**Q: 如何卸载？**  
A: 执行仓库中的 `uninstall.sh`，或手动删除 `~/.local/bin/tiger-tv.py`。

## License

MIT
