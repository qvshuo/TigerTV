# 3.3.0 重构验证记录

验证日期：2026-10-25。当前范围为 macOS arm64、Android TV 与 macOS 原生 CLI；Windows 已从源码目标和发布流程撤下。

## 自动检查

| 检查 | 结果 |
|---|---|
| Python 业务参考 unittest | 39 项通过 |
| Swift 原业务参考 XCTest | 33 项通过 |
| KMP core JVM | 11 项通过 |
| KMP core macOS Native | 同一组 11 项通过 |
| Native bridge 生命周期测试 | 1 项通过 |
| Android 单元测试 | 30 项通过 |
| macOS Release 构建、启动 | 通过 |
| Android Debug universal APK 构建 | 通过，包含 armeabi-v7a / arm64-v8a |
| Native CLI 进程级差异测试 | 通过 |
| Swift → Kotlin/Native framework 冒烟测试 | 通过 |
| CLI 发布包校验、安装、旧名称兼容、卸载 | 通过，下载阶段使用本地发布包替身 |

CLI 差异测试比较了搜索 JSON、详情 JSON、Quantumult X 文本，并覆盖中文/emoji 参数和来源路径、代理 API、坏站继续、10MB 上限、参数/业务退出码以及帮助、版本和日志。原生进程执行时 PATH 不含 Java/Python。

## macOS

宿主：Apple Silicon、macOS 27、Swift 6.4；产品部署目标仍为 macOS 26+。保留 SwiftUI 与 AVPlayer，实际 GUI 启动通过。

使用生成的 H.264/AAC 测试素材验证了实际 AVPlayer 适配器的 HLS/MP4 播放推进、暂停、跳转至第 6 秒和释放。测试静音，不使用第三方影视资源。

## Android TV

使用现有 Android 14 / API 34 / arm64 TV 模拟器，启动时指定只读模式，不修改保存的模拟器数据。

实测通过：

- 搜索、detail 封面兜底、海报显示。
- 新结果首卡焦点、向右到第 2 卡、向下滚动到第 8 卡。
- 进入剧集后首集焦点，HLS 与 MP4 均解码播放至测试片尾。
- 404 播放错误浮层自动聚焦“重试”；按确认后实际重新加载，并在再次失败后恢复重试焦点。
- 返回剧集和结果页后焦点恢复。

验证中修复了同 URL 快速解析可能被 UI 状态合并、导致播放器未重新加载的问题。两端均以播放修订号触发重新装载；TV 错误按钮增加显式焦点请求。

## 尚需发布前验收

- v7a Android 14 实体电视安装、硬件解码与遥控器手感；目前仅验证 APK ABI 内容及 arm64 TV 模拟器。
- macOS 系统明暗模式、全屏和实际设备上的完整视觉验收。
- 真实资源站的可用性会变化；本次主要使用隔离的本地 API/媒体验证业务一致性。
- macOS 构建产物未做 Developer ID 签名/公证；Android 本地产物为 Debug 签名。正式发布继续通过手动工作流，Android 使用既有固定密钥。

构建、测试、打包和签名方法见 [跨平台开发文档](cross-platform.md)。
