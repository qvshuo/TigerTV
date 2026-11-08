# 开发与发布

TigerTV 的业务实现集中在 Kotlin Multiplatform `core`。Android 直接调用，macOS 经 `nativeBridge` 的静态 framework 调用；CLI 使用 Kotlin/Native。三端共用搜索、聚合、QX 扫描、缓存和历史逻辑。

| 目录 | 职责 |
|---|---|
| `core/` | 配置、MacCMS、聚合、QX 扫描、缓存、历史 |
| `nativeBridge/` | Swift 异步 JSON 桥接 |
| `cli/` | macOS arm64、Linux x86_64 / arm64 原生命令行 |
| `macOS/` | SwiftUI、AVPlayer、Xcode 工程 |
| `AndroidTV/` | Compose TV、Media3 应用模块 |
| `assets/branding/` | 品牌母图、README 图标与网站 favicon |
| `docs/fixtures/` | 核心、双端共用契约夹具 |
| `scripts/` | 契约、桥接、播放器与发布校验 |
| `skills/` | Agent Skill、CLI 安装器及源配置 |

接口和交互见[契约](contract.md)，实现限制见 [AGENTS.md](../AGENTS.md)。生产源配置位于 `skills/references/LunaTV-config.json`，由定时工作流同步上游。

macOS 源码按职责组织在 `App/Application`（入口、页面协调、ViewModel）、`App/Data`（核心适配、模型与封面缓存）、`App/UI`（首页、结果、详情、播放器及共用组件）和 `App/Resources`。SwiftPM 的测试库只包含 `App/Data` 中不依赖原生桥接的代码；Xcode 构建完整应用。Android 页面位于 `ui/home`、`ui/results`、`ui/detail`、`ui/player`，平台数据适配与封面 URL 工具集中在 `data`。core 的热门接口模型与分类定义放在 `HotSearchModels.kt`，业务仍由 `CoreService` 统一实现。

CoreService 负责业务入口与协调，Network 负责 HTTP 和网络约束，Parsing 负责 MacCMS 数据，QuanxCollector 独占 QX 网络扫描和 HTML/HLS 解析，Models 统一模型，Storage 负责平台持久化 I/O；缓存和历史仍由 CoreService 管理。普通播放直接交给原生播放器，不经过额外地址解析。历史增删在 core 内原子执行，避免多窗口旧快照覆盖；平台仅按播放会话去重回调，由 core 判定书签是否需要落盘。macOS 窗口关闭取消所属请求，退出与关闭会等待搜索历史和播放记录写入。

图标用途与各平台尺寸见[品牌图标说明](../assets/branding/README.md)。

## 构建与检查

工具链：Kotlin 2.4.21、Gradle 9.8.2、AGP 9.5.1、JDK 21、Android SDK 37；Xcode 27.2（27B52）、Swift 6.4.2、macOS SDK 27.2。应用最低 macOS 26／Android 14；macOS 仅 arm64，Android APK 同时包含 v7a 和 arm64。

Android Studio 打开 `AndroidTV/`，Xcode 打开 `macOS/TigerTV.xcodeproj`。Android SDK 配在 `AndroidTV/local.properties` 或 `ANDROID_HOME`。根 Gradle 只构建核心、桥接和 CLI，无需 Android SDK；Android 工程直接引用同一份核心源码，产物隔离到 `AndroidTV/build/core`。两个入口通过 `AndroidTV/gradle` 符号链接共用 Wrapper、Java 21 规则和插件版本。

```bash
./gradlew :core:jvmTest :core:macosArm64Test :nativeBridge:macosArm64Test :cli:linkReleaseExecutableMacosArm64
python3 scripts/check-cli-contract.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
swift test --package-path macOS
xcodebuild -project macOS/TigerTV.xcodeproj -scheme TigerTV -destination 'platform=macOS,arch=arm64' CODE_SIGNING_ALLOWED=NO build
./AndroidTV/gradlew testDebugUnitTest lintDebug assembleDebug
# With an Android TV emulator or device connected:
./AndroidTV/gradlew connectedDebugAndroidTest
```

Xcode 自动构建 framework，递归跟踪 Kotlin 输入及产物。Gradle 与 Xcode 原生构建应顺序执行，避免同时修改同一 framework。

Linux 原生 CLI 使用 Ktor Curl 引擎，共享 POSIX 文件和控制台适配。发布包以 Ubuntu 24.04（glibc 2.39）为验证基线，依赖系统 `libcurl.so.4` 和 CA 证书。Ubuntu 可安装 `libcurl4-openssl-dev ca-certificates`；运行无需 JDK/JVM。CI 在 Linux x86_64 runner 编译两个 Linux 目标，再分别在 x86_64 和 arm64 runner 上执行 CLI 契约检查；x86_64 同时运行核心 Native 测试。Kotlin/Native 编译主机使用 Linux x86_64 或 macOS。

```bash
./gradlew :cli:linkReleaseExecutableLinuxX64 :core:linuxX64Test
python3 scripts/check-cli-contract.py cli/build/bin/linuxX64/releaseExecutable/tigertv.kexe
# 在支持的编译主机上交叉编译 arm64，再将产物复制到 arm64 主机运行检查：
./gradlew :cli:linkReleaseExecutableLinuxArm64
python3 scripts/check-cli-contract.py cli/build/bin/linuxArm64/releaseExecutable/tigertv.kexe
```

桥接进程检查：

```bash
./gradlew :nativeBridge:linkDebugFrameworkMacosArm64
swiftc -parse-as-library -F nativeBridge/build/bin/macosArm64/debugFramework -framework TigerTVCore scripts/check-native-bridge.swift -o /tmp/check-native-bridge
/tmp/check-native-bridge
swiftc -swift-version 6 -parse-as-library -F nativeBridge/build/bin/macosArm64/debugFramework -framework TigerTVCore macOS/App/Data/Models.swift macOS/App/Data/HTTPConfiguration.swift macOS/App/Data/LoadResult.swift macOS/App/Data/TigerTVError.swift macOS/App/Data/SharedCoreRepository.swift macOS/App/Application/TigerTVViewModel.swift macOS/App/UI/Player/AVPlayerController.swift scripts/check-macos-state.swift -o /tmp/check-macos-state
/tmp/check-macos-state
```

`check-macos-state` 验证生产 ViewModel 的原始 URL 选集与重试、AVURLAsset 地址交接、异常地址拒绝、迟到响应丢弃、原始播放会话进度隔离和释放。Core 测试覆盖 QX 的 HTML／重定向／全部 HLS 关系与取消，双端解码 `fetch-playback.sample.json`，Android 单测验证选集／重试不调用 Repository。TV 切源回归同时验证简介全文显示、长短简介互换、焦点与滚动位置、失败、迟到响应及字体缩放；播放器输入回归验证非法地址及同步建源失败不会崩溃或写入进度。设备测试继续覆盖真实媒体加载、暂停、跳转、返回、错误重试及释放；实体电视验收需在目标设备另行执行。

`scripts/serve-playback-fixtures.py DIR` 仅暴露指定测试目录中的媒体与测试 API；`scripts/check-macos-playback.swift` 验证实际 AVPlayer 播放、暂停、跳转与释放，并经 AppKit 事件分发检查方向键跳转与连续按键时的播放状态。

键盘播放检查使用至少 45 秒的 MP4/HLS 点播夹具，编译后传入本机测试媒体 URL：

```bash
swiftc -swift-version 6 -parse-as-library macOS/App/Data/HTTPConfiguration.swift macOS/App/UI/Player/AVPlayerController.swift macOS/App/UI/Player/PlayerContainer.swift scripts/check-macos-playback.swift -o /tmp/check-macos-playback
/tmp/check-macos-playback http://127.0.0.1:18766/index.m3u8 http://127.0.0.1:18766/sample.mp4
```

## 发布

当前版本 4.4.2，Android 默认 versionCode 26，macOS build 19。4.4.2 发布使用 versionCode 27；手动发布的 versionCode 必须大于仓库默认值。

PR 执行检查；仅手动 `workflow_dispatch` 发布。Actions 使用 Xcode 27.2 版本别名，校验 Xcode 与 macOS SDK 27.2，不锁定构建号；托管镜像更新前允许使用该别名指向的 RC。产物为未签名 macOS ZIP、Android universal APK，以及 macOS arm64、Linux x86_64 / arm64 CLI tar.gz 和 SHA-256。

Android 使用 `~/.android/debug.keystore`，别名 `androiddebugkey`，采用标准 debug-key 凭据；CI 从四项 Android 签名 secrets 恢复。证书 SHA-256 为 `0d81ab7cb1a16d58d91a953fe62049eb77fc840cc544a297ae1854f54bc73b96`。每次发布增加 versionCode。发布前在实际设备验证遥控器、返回、HLS/MP4、重试、全屏、中文输入与关闭释放；自动测试不能代替设备验收。

保持依赖更新检查开启，不为消除提示而屏蔽诊断。Kotlin 2.4.21 在 Gradle 9.8.2 下存在上游 Native 属性弃用及部分调试符号诊断；未采用 Beta 或隐藏这些诊断。

首页热门卡片与搜索结果卡片共用平台原生组件上的样式封装：macOS 使用 SwiftUI Button、GroupBox 与 Layout；Android TV 使用默认 TV Material 3 主题及 Button、Card、FilterChip、InputChip、ListItem、Surface，搜索输入与布局使用 Foundation。热门接口与缓存属于 core，样例见 `fixtures/douban-hot.sample.json`（上游）与 `fixtures/hot-search.sample.json`（共享输出）。豆瓣图片带 Referer，并逐行并发加载；具体字号、间距与平台交互以 `contract.md` 为准。
