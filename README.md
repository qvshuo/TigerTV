# 小老虎爱看剧 (TigerTV)

基于资源站 API 的影视搜索、播放/下载链接提取与 Quantumult X 直连规则生成工具。

- **零依赖**：仅使用 Python 3 标准库
- **多源并发**：同时搜索 20+ 资源站点
- **规则生成**：一键提取域名并输出 Quantumult X 规则
- **JSON 输出**：`search` / `fetch` 直接输出结构化数据，便于管道和脚本解析

## 安装和卸载

```bash
# 安装到 ~/.local/bin
curl -fsSL https://raw.githubusercontent.com/qvshuo/TigerTV/main/scripts/install.sh | bash

# 加入 PATH（bash）
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

# 加入 PATH（zsh）
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

卸载：执行 `bash scripts/uninstall.sh`，或手动删除 `~/.local/bin/tigertv-cli.py`。

## 快速开始

```bash
# 搜索视频
$ tigertv-cli.py search 逐玉

# 获取播放链接（site 需完整匹配，如 🎬-爱奇艺-）
$ tigertv-cli.py fetch --site "🎬-爱奇艺-" --vod_id 73480

# 生成 Quantumult X 直连规则
$ tigertv-cli.py quanx 逐玉

# 查看日志
$ tigertv-cli.py logs
```

## 命令速查

| 命令 | 用法 | 说明 |
|---|---|---|
| `search` | `<keyword>` | 搜索视频，每站最多 100 条，输出 JSON |
| `fetch` | `--site <name> --vod_id <id>` | 按站点获取播放/下载链接，输出 JSON |
| `quanx` | `<keyword>` | 搜索并提取域名，输出 Quantumult X 规则 |
| `logs` | `[--full] [--clear]` | 查看日志，`--full` 显示全部，`--clear` 清空 |

**全局参数**

| 参数 | 说明 |
|---|---|
| `--source <path>` | 指定本地 JSON 配置文件，跳过远程和缓存 |

## 输出示例

### search

```json
{
  "keyword": "逐玉",
  "results": [
    {"site": "🎬-爱奇艺-", "vod_id": 73480, "vod_name": "逐玉", "vod_time": "...", "vod_remarks": "全40集"}
  ]
}
```

### fetch

```json
{
  "vod_id": 73480,
  "site": "🎬-爱奇艺-",
  "vod_play_url": [{"name": "第01集", "url": "https://..."}],
  "vod_down_url": []
}
```

### quanx

```text
; 资源站 API 域名
host-suffix, example.com, direct
; 播放/下载域名
host-suffix, example.com, direct
; m3u8 域名
host-suffix, example.com, direct
```

## 致谢

- 站点配置数据来自 [LunaTV-config](https://github.com/hafrey1/LunaTV-config)

## License

MIT
