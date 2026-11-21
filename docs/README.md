# 开发与发布

TigerTV 长期维护 Android TV、Web/PWA 和 CLI。原 macOS SwiftUI 客户端已停止开发与维护。归档源码和桥接保留在原目录，已退出默认 Gradle 构建、CI 校验和发布。macOS、Windows 和 Linux 的图形界面统一使用 Web/PWA。

| 目录 | 职责 |
|---|---|
| `AndroidTV/` | Compose TV、Media3、系统遥控焦点，进程内调用 Kotlin Core |
| `web/` | Svelte 5 Runes、TypeScript、Vite、Tailwind CSS 4、Video.js 10 客户端及 Bun 同源元数据服务 |
| `cli/` | macOS arm64、Linux x86_64 / arm64 原生命令行 |
| `core/` | Android TV 和 CLI 共用的配置、MacCMS、聚合、QX 扫描、缓存、历史等 |
| `shared/` | Android TV 与 Web 共用的 HLS 通用规则参数 |
| `docs/fixtures/` | 上游协议、数据契约及播放测试夹具 |
| `macOS/`、`nativeBridge/` | 停止维护的 SwiftUI 客户端及其静态桥接，供参考 |
| `skills/` | Agent Skill、CLI 安装器及来源配置 |

Android TV 与 Kotlin/Native CLI 在进程内使用 Kotlin Core，独立请求原始来源。Web 由 Bun 构建和运行，Bun 同时提供静态 Svelte SPA 和元数据 Gateway。浏览器直连媒体 CDN 和用户配置的弹幕服务。两端共用 [数据与交互契约](contract.md)、夹具和通用去广告参数。

Core 的 `CoreService` 负责业务协调，`Network` 负责 HTTP，`Parsing` 处理 MacCMS，`QuanxCollector` 单独负责 QX 的 HTML/HLS 扫描。生产源配置在 `skills/references/LunaTV-config.json`，由现有工作流同步。Web 网络与兼容性见 [Web 开发说明](development/web.md)，去广告研究见 [FlowScan 技术记录](development/flowscan.md)。

## 构建与检查

Android Studio 打开 `AndroidTV/`，使用 Kotlin 2.4.21、Gradle 9.8.2、AGP 9.5.1、JDK 21、Android SDK 37。Android SDK 可配置在 `ANDROID_HOME` 或 `AndroidTV/local.properties`。Android 14+，通用 APK 包含 v7a 和 arm64。根 Gradle 入口构建 CLI 和 Core，无需 Android SDK。两个入口共用 Wrapper、Java 21 规则和插件版本。

```bash
./AndroidTV/gradlew :core:jvmTest testDebugUnitTest lintDebug assembleDebug
./AndroidTV/gradlew connectedDebugAndroidTest
./gradlew :core:macosArm64Test :cli:linkReleaseExecutableMacosArm64
python3 scripts/check-cli-contract.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
(cd web && bun install --frozen-lockfile && bun run check && bun test && bun run build)
docker compose -f web/compose.yaml build
```

根 Gradle 生成 Kotlin 广告规则输入，Web 直接导入同一 JSON。Web 构建输出位于 `web/dist/`。在 `web/` 中使用 `bun run dev` 热更新，使用 `bun run build && bun run start` 核对生产服务和 PWA 安装。

Linux 原生 CLI 使用 Ktor Curl 引擎，共享 POSIX 文件和控制台适配。发布包以 Ubuntu 24.04（glibc 2.39）为验证基线，依赖系统 `libcurl.so.4` 和 CA 证书。Ubuntu 可安装 `libcurl4-openssl-dev ca-certificates`；运行无需 JDK/JVM。CI 在 Linux x86_64 runner 编译两个 Linux 目标，再分别在 x86_64 和 arm64 runner 上执行 CLI 契约检查；x86_64 同时运行核心 Native 测试。Kotlin/Native 编译主机使用 Linux x86_64 或 macOS。

```bash
./gradlew :cli:linkReleaseExecutableLinuxX64 :core:linuxX64Test
python3 scripts/check-cli-contract.py cli/build/bin/linuxX64/releaseExecutable/tigertv.kexe
# 在支持的编译主机上交叉编译 arm64，再将产物复制到 arm64 主机运行检查：
./gradlew :cli:linkReleaseExecutableLinuxArm64
python3 scripts/check-cli-contract.py cli/build/bin/linuxArm64/releaseExecutable/tigertv.kexe
```

## 发布

当前版本 4.4.2，Android 默认 versionCode 26。4.4.2 发布使用 versionCode 27；手动发布的 versionCode 必须大于仓库默认值。

PR 执行检查；仅手动 `workflow_dispatch` 发布。Actions 使用 Xcode 27.2 版本别名，校验 Xcode 与 macOS SDK 27.2，不锁定构建号；托管镜像更新前允许使用该别名指向的 RC。发布产物包括 Web/PWA Linux x86_64 Docker 镜像归档、Android universal APK、macOS arm64 与 Linux x86_64/arm64 CLI tar.gz 及各自的 SHA-256。Xcode 工具链用于编译 macOS 原生 CLI 和运行 Core Native 测试。

Android 使用 `~/.android/debug.keystore`，别名 `androiddebugkey`，采用标准 debug-key 凭据；CI 从四项 Android 签名 secrets 恢复。证书 SHA-256 为 `0d81ab7cb1a16d58d91a953fe62049eb77fc840cc544a297ae1854f54bc73b96`。每次发布增加 versionCode。发布前在实际设备验证遥控器、返回、HLS/MP4、重试、全屏、中文输入与关闭释放；自动测试不能代替设备验收。

保持依赖更新检查开启，不为消除提示而屏蔽诊断。Kotlin 2.4.21 在 Gradle 9.8.2 下存在上游 Native 属性弃用及部分调试符号诊断；未采用 Beta 或隐藏这些诊断。
