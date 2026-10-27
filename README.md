# TigerTV · 小老虎爱看剧

影视资源搜索、选集播放与 Agent CLI。

| 平台 | 客户端 | 播放器 |
|---|---|---|
| macOS 26+ arm64 | SwiftUI | AVPlayer |
| Android 14+ TV | Compose for TV，v7a / arm64 | Media3 |

业务逻辑由 Kotlin Multiplatform 共享，macOS CLI 为 Kotlin/Native 原生可执行文件。macOS GUI 和 CLI 均不需要 Java 或 Python。Windows 版本暂缓开发。

## Agent CLI

macOS 安装：

```bash
curl -fsSL https://raw.githubusercontent.com/qvshuo/TigerTV/main/skills/scripts/install.sh | bash
```

安装验证 SHA-256，不修改 PATH。当前原生 CLI 仅提供 macOS arm64。

```bash
tigertv search 逐玉
tigertv fetch --site '🎬站点完整名称' --vod_id 42
tigertv quanx 逐玉
tigertv logs
```

search/fetch 输出 JSON；quanx/logs 输出文本。支持 `tigertv --source sources.json search 逐玉` 离线指定来源配置。Agent 工作流见 [Skill](skills/SKILL.md)。下载剧集另需 yt-dlp/ffmpeg。

新安装脚本需要包含原生 CLI 资产的 Release。首个此类 Release 发布前，可直接使用本地构建产物。原 Python 文件暂留作业务与测试参考，不再是新客户端的运行时依赖。

## 开发

构建需要 JDK 21，但不会把 JVM 打进桌面应用。

```bash
./gradlew :core:jvmTest :core:macosArm64Test :cli:linkReleaseExecutableMacosArm64
xcodebuild -project macOS/TigerTV.xcodeproj -scheme TigerTV -destination 'platform=macOS' build
# Android，需设置 ANDROID_HOME
./gradlew -Pandroid=true :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

构建环境、Swift 桥接契约、打包、签名、测试和发布详见 [跨平台开发文档](docs/cross-platform.md)。发布只通过手动 workflow_dispatch 触发，Android 使用固定签名。

[验证记录](docs/verification.md) · [共享 API 契约](shared/api-contract/README.md) · [MIT License](LICENSE)
