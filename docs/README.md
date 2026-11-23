# 开发与发布

Android TV（Compose TV、Media3）和 Kotlin/Native CLI 共用 `core/`，直接访问第三方来源。Web/PWA 由 Svelte 静态构建，Bun 同端口提供 Web 和元数据。两端共用 `shared/` 的规则与 [契约夹具](fixtures/)。`macOS/` 和 `nativeBridge/` 已归档，桌面图形界面使用 Web/PWA。

## 检查

用 Android Studio 打开 `AndroidTV/`。根 Gradle 构建 Core 和 CLI，无需 Android SDK。两个 Gradle 入口共用 Wrapper、版本目录和 Java 21 配置。Android 构建的 Core 输出保持独立。

```sh
./gradlew :core:jvmTest :core:macosArm64Test :cli:linkReleaseExecutableMacosArm64
python3 scripts/check-cli-contract.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
./AndroidTV/gradlew :core:jvmTest testDebugUnitTest lintDebug assembleDebug
./AndroidTV/gradlew connectedDebugAndroidTest
(cd web && bun install --frozen-lockfile && bun run check && bun test && bun run build)
```

Linux 的原生任务为 `:core:linuxX64Test`、`:cli:linkReleaseExecutableLinuxX64` 和 `:cli:linkReleaseExecutableLinuxArm64`。分别在对应架构运行同一 CLI 契约检查。Linux 发布以 Ubuntu 24.04 为基线，运行依赖系统 libcurl 和 CA 证书。

## 发布

PR 执行检查，发布由 `workflow_dispatch` 触发。产物为双 ABI Android APK、Web 镜像归档、macOS arm64 和 Linux x86_64 / arm64 CLI，附 SHA-256。发布前在实体 TV 核对遥控器与播放。

Android 使用 `~/.android/debug.keystore` 的 `androiddebugkey`，CI 从 Android 签名 secrets 恢复同一密钥。证书 SHA-256 为 `0d81ab7cb1a16d58d91a953fe62049eb77fc840cc544a297ae1854f54bc73b96`。发布时提升 versionCode，不生成替代密钥。

[跨端契约](contract.md) · [Web 部署](development/web.md) · [HLS 研究](development/flowscan.md) · [来源与博彩插播验收](development/android-tv-sources.md) · [TV 弹幕验证](development/android-danmaku.md)
