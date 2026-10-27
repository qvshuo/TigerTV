<p align="center">
  <img width="128" height="128" src="macOS/App/Assets.xcassets/AppIcon.appiconset/icon_256x256.png" alt="TigerTV 图标">
</p>

# 小老虎爱看剧（TigerTV）

跨平台影视搜索与播放工具，包含 macOS 应用、Android TV 应用，以及供用户和 AI Agent 使用的命令行工具。

## 特性

- **macOS 应用**：搜索多个资源站，选集播放，支持系统明暗模式
- **Android TV 应用**：为电视遥控器优化的搜索、选集与播放体验
- **命令行工具**：搜索影视资源、获取播放和下载链接、生成 Quantumult X 直连规则、查看日志
- **Agent Skill**：为 AI Agent 提供影视搜索、链接获取、直连规则生成和下载流程指引

## 截图

### macOS

<table>
  <tr>
    <td width="50%"><img src="screenshots/macOS/1.png" width="100%" alt="macOS 应用截图 1"></td>
    <td width="50%"><img src="screenshots/macOS/2.png" width="100%" alt="macOS 应用截图 2"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/macOS/3.png" width="100%" alt="macOS 应用截图 3"></td>
    <td width="50%"><img src="screenshots/macOS/4.png" width="100%" alt="macOS 应用截图 4"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/macOS/5.png" width="100%" alt="macOS 应用截图 5"></td>
    <td width="50%"></td>
  </tr>
</table>

### Android TV

<table>
  <tr>
    <td width="50%"><img src="screenshots/AndroidTV/1.png" width="100%" alt="Android TV 应用截图 1"></td>
    <td width="50%"><img src="screenshots/AndroidTV/2.png" width="100%" alt="Android TV 应用截图 2"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/AndroidTV/3.png" width="100%" alt="Android TV 应用截图 3"></td>
    <td width="50%"><img src="screenshots/AndroidTV/4.png" width="100%" alt="Android TV 应用截图 4"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/AndroidTV/5.png" width="100%" alt="Android TV 应用截图 5"></td>
    <td width="50%"></td>
  </tr>
</table>

## 快速开始

### macOS 应用

1. 从 [Releases](https://github.com/qvshuo/TigerTV/releases) 下载 `TigerTV-macOS-arm64.zip`。
2. 解压并将「小老虎爱看剧」拖入「应用程序」文件夹。

**系统要求：** macOS 26+（Apple Silicon）。

应用目前未签名。如果 macOS 阻止打开，可在终端执行：

```bash
xattr -dr com.apple.quarantine "/Applications/小老虎爱看剧.app"
```

### Android TV 应用

1. 从 [Releases](https://github.com/qvshuo/TigerTV/releases) 下载 `TigerTV-AndroidTV-universal.apk`。
2. 将 APK 安装到 Android TV 设备。

**系统要求：** Android 14+。

从旧签名版本升级时，需要先卸载旧版本。卸载会删除旧版本的应用数据。

### 命令行工具

目前支持 macOS（Apple Silicon），无需安装 Java 或 Python。

一键安装：

```bash
curl -fsSL https://raw.githubusercontent.com/qvshuo/TigerTV/main/skills/scripts/install.sh | bash
```

安装脚本不会修改 PATH。如 `~/.local/bin` 尚未加入 PATH，在 zsh 中可执行：

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

#### 常用命令

```bash
# 搜索剧集
tigertv search 逐玉

# 获取播放 / 下载链接：使用搜索结果中的完整站点名和视频 ID
tigertv fetch --site "🎬站点完整名称" --vod_id 73480

# 生成 Quantumult X 直连规则
tigertv quanx 逐玉

# 查看日志
tigertv logs
```

也可以通过 `tigertv --source sources.json search 逐玉` 使用本地来源配置。[Agent Skill](skills/SKILL.md) 提供完整的 Agent 工作流；下载剧集另需 `yt-dlp` 和 `ffmpeg`。

## 开发

构建、测试和发布说明见 [跨平台开发文档](docs/cross-platform.md)，项目实现细节见 [AGENTS.md](AGENTS.md)。

## 致谢

站点配置数据来源：[LunaTV-config](https://github.com/hafrey1/LunaTV-config)。

## License

[MIT](LICENSE)
