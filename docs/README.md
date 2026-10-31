# 开发与发布

TigerTV 的业务实现集中在 Kotlin Multiplatform `core`。Android 直接调用，macOS 经 `nativeBridge` 的静态 framework 调用；CLI 使用 Kotlin/Native。三端共用搜索、聚合、播放解析、缓存和历史逻辑。

| 目录 | 职责 |
|---|---|
| `core/` | 配置、MacCMS、聚合、播放解析、缓存、历史 |
| `nativeBridge/` | Swift 异步 JSON 桥接 |
| `cli/` | macOS arm64 命令行 |
| `macOS/` | SwiftUI、AVPlayer、Xcode 工程 |
| `AndroidTV/` | Compose TV、Media3 应用模块 |
| `docs/fixtures/` | 核心、双端共用契约夹具 |
| `scripts/` | 契约、桥接、播放器和发布校验 |
| `skills/` | Agent Skill、CLI 安装器及源配置 |

接口和交互见[契约](contract.md)，实现限制见 [AGENTS.md](../AGENTS.md)。生产源配置位于 `skills/references/LunaTV-config.json`，由定时工作流同步上游。

## 构建与检查

工具链：Kotlin 2.4.21、Gradle 9.8.2、AGP 9.5.0、JDK 21、Android SDK 37；Xcode 27.2（27B52）、Swift 6.4.2、macOS SDK 27.2。应用最低 macOS 26／Android 14；macOS 仅 arm64，Android APK 同时包含 v7a 和 arm64。

Android Studio 打开 `AndroidTV/`，Xcode 打开 `macOS/TigerTV.xcodeproj`。Android SDK 配在 `AndroidTV/local.properties` 或 `ANDROID_HOME`。根 Gradle 只构建核心、桥接和 CLI，无需 Android SDK；Android 工程直接引用同一份核心源码，产物隔离到 `AndroidTV/build/core`。两个入口通过 `AndroidTV/gradle` 符号链接共用 Wrapper、Java 21 规则和插件版本。

```bash
./gradlew :core:jvmTest :core:macosArm64Test :nativeBridge:macosArm64Test :cli:linkReleaseExecutableMacosArm64
python3 scripts/check-cli-contract.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
swift test --package-path macOS
xcodebuild -project macOS/TigerTV.xcodeproj -scheme TigerTV -destination 'platform=macOS,arch=arm64' CODE_SIGNING_ALLOWED=NO build
./AndroidTV/gradlew testDebugUnitTest lintDebug assembleDebug
```

Xcode 自动构建 framework，递归跟踪 Kotlin 输入及产物；不要恢复每次必跑的脚本。Gradle 与 Xcode 原生构建应顺序执行，避免同时修改同一 framework。

桥接进程检查：

```bash
./gradlew :nativeBridge:linkDebugFrameworkMacosArm64
swiftc -parse-as-library -F nativeBridge/build/bin/macosArm64/debugFramework -framework TigerTVCore scripts/check-native-bridge.swift -o /tmp/check-native-bridge
/tmp/check-native-bridge
```

`scripts/serve-playback-fixtures.py DIR` 仅暴露指定测试目录中的媒体与测试 API；`scripts/check-macos-playback.swift` 验证实际 AVPlayer 播放、暂停、跳转与释放。

## 发布

当前版本 4.0.0，Android versionCode 13，macOS build 10。手动发布的 versionCode 必须大于仓库当前值，默认 14。

PR/main 执行检查；仅手动 `workflow_dispatch` 发布。Actions 使用 Xcode 27.2 版本别名，校验 Xcode 与 macOS SDK 27.2，不锁定构建号；托管镜像更新前允许使用该别名指向的 RC。产物为未签名 macOS ZIP、Android universal APK、macOS CLI tar.gz 及 SHA-256。

Android 固定复用 Interlude 的既有 debug keystore，CI 从现有四项签名 secrets 恢复；不得临时生成密钥。每次发布增加 versionCode。发布前在实际设备验证遥控器、返回、HLS/MP4、重试、全屏、中文输入与关闭释放；自动测试不能代替设备验收。

保持依赖更新检查开启，不为消除提示而屏蔽诊断。Kotlin 2.4.21 在 Gradle 9.8.2 下存在上游 Native 属性弃用及部分调试符号诊断；未采用 Beta 或隐藏这些诊断。
