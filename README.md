<p align="center">
  <img width="128" height="128" src="macOS/App/Assets.xcassets/AppIcon.appiconset/icon_256x256.png" alt="TigerTV icon">
</p>

# 小老虎爱看剧（TigerTV）

跨平台影视搜索与播放工具，包含 macOS 应用、Android TV 应用，以及命令行工具 `tigertv-cli.py` 和 Agent 可用的 `skills/SKILL.md`。

## 特性

- **macOS 应用**：聚合多个资源站，支持搜索与播放剧集
- **Android TV 应用**：为电视遥控器优化的搜索、选集与播放体验
- **命令行工具**：搜索影视资源、获取播放/下载链接、生成 Quantumult X 直连规则、查看日志
- **Agent Skill**：基于 CLI 封装影视搜索、链接获取和 QX 直连规则生成，并提供下载流程建议

## 截图

### macOS

<table>
  <tr>
    <td width="50%"><img src="screenshots/macOS/1.png" width="100%"></td>
    <td width="50%"><img src="screenshots/macOS/2.png" width="100%"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/macOS/3.png" width="100%"></td>
    <td width="50%"><img src="screenshots/macOS/4.png" width="100%"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/macOS/5.png" width="100%"></td>
    <td width="50%"></td>
  </tr>
</table>

### Android TV

<table>
  <tr>
    <td width="50%"><img src="screenshots/AndroidTV/1.png" width="100%"></td>
    <td width="50%"><img src="screenshots/AndroidTV/2.png" width="100%"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/AndroidTV/3.png" width="100%"></td>
    <td width="50%"><img src="screenshots/AndroidTV/4.png" width="100%"></td>
  </tr>
  <tr>
    <td width="50%"><img src="screenshots/AndroidTV/5.png" width="100%"></td>
    <td width="50%"></td>
  </tr>
</table>

## 快速开始

### macOS 应用

1. 从 [Releases](https://github.com/qvshuo/TigerTV/releases) 下载 `TigerTV-macOS-arm64-<version>.zip`
2. 解压并拖入「应用程序」文件夹  

首次启动前执行：

```shell
xattr -cr "/Applications/小老虎爱看剧.app"
```

**系统要求：** macOS 26+（Apple Silicon）

### Android TV 应用

1. 从 [Releases](https://github.com/qvshuo/TigerTV/releases) 下载 `TigerTV-AndroidTV-universal-<version>.apk`
2. 安装到 Android TV 设备

**系统要求：** Android 6.0+（API 23+）

### 命令行工具

一键安装：

```bash
curl -fsSL https://raw.githubusercontent.com/qvshuo/TigerTV/main/skills/scripts/install.sh | bash
```

安装脚本不会修改 PATH；如 `~/.local/bin` 尚未加入 PATH，可手动配置：

```shell
# bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

# zsh
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

#### 常用命令

```shell
# 搜索剧集
tigertv-cli.py search 逐玉

# 获取播放 / 下载链接（site 需完整匹配）
tigertv-cli.py fetch --site "🎬-爱奇艺-" --vod_id 73480

# 生成 Quantumult X 直连规则
tigertv-cli.py quanx 逐玉

# 查看日志
tigertv-cli.py logs
```

日志路径：

```
/tmp/tigertv-cli.log
```

## 致谢

站点配置数据来源：[LunaTV-config](https://github.com/hafrey1/LunaTV-config)

## License

MIT
