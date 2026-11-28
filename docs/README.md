# 开发与发布

## 模块与状态

| 模块 | 职责与依赖 |
|---|---|
| `core/` | Kotlin Domain 保存领域模型。Core 使用 Domain 实现来源、搜索、匹配及业务策略，CoreService 提供公共入口。HistoryStore 拥有搜索历史、书签和播放进度的持久化。搜索、配置、热门推荐和封面缓存各按自己的生命周期管理。 |
| `AndroidTV/` | Compose TV 和 Media3 依赖 Core。ViewModel 拥有导航关联的搜索、选中项及播放状态，DetailRequests 拥有详情缓存和在途请求，DanmakuState 拥有每次播放的弹幕会话。 |
| `cli/` | Kotlin/Native 搜索、详情和日志命令，依赖 Core。 |
| `web/` | Svelte/PWA 页面拥有导航、选中项与搜索状态，详情会话拥有缓存及请求。播放器拥有媒体和时钟，弹幕会话拥有请求、轨道和选择，storage 持久化用户状态。Bun Gateway 提供静态 Web 和白名单元数据，媒体直连。 |
| `shared/` | 手动维护的来源配置，以及分类、默认值、HLS、弹幕及超时的权威 JSON 定义。数据行为见[跨端契约](contract.md)及[夹具](fixtures/)。 |

活跃产品为 Android TV、Web/PWA 和 CLI。桌面和移动端使用 Web/PWA。macOS SwiftUI 和专属 bridge 已归档至 `archive/macos/`。CLI 支持 macOS arm64 和 Linux x86_64、arm64。

## CLI 使用

从 GitHub Releases 下载对应平台的 CLI 归档，解压后将 `tigertv` 放进 PATH。也可在仓库根目录运行 `bash scripts/install-cli.sh`，脚本下载发布包并验证 SHA-256。

```sh
tigertv
tigertv --help
tigertv search '两京十五日'
tigertv fetch --site '🎬360 资源' --video-id 123
tigertv --config shared/LunaTV-config.json search '两京十五日'
tigertv logs
```

无参数与 `--help` 显示同一份帮助。`fetch` 使用搜索结果中的完整来源名和 `vod_id`。搜索和详情输出 JSON，详情保留播放和下载线路。

## 来源维护

直接编辑 [LunaTV-config.json](../shared/LunaTV-config.json)。只有名称带 `🎬` 的来源可启用。名称带 `🔞` 的条目留作备查。修改来源后检查 API 和详情返回的播放线路，失效来源直接删除。

Core 从 GitHub 主分支读取该文件并缓存。迁移前的客户端仍请求旧地址，需要升级。Web 镜像打包这份文件，修改默认名单后重新构建镜像。CLI 指定 `--config` 时直接读取本地文件。

## 开发命令

以下验证命令供用户明确要求时使用。CI 保留自动检查。

用 Android Studio 打开 `AndroidTV/`。根 Gradle 构建 Core 和 CLI，无需 Android SDK。两个 Gradle 入口共用 Wrapper、版本目录和 Java 21 配置。Android 构建的 Core 输出保持独立。

```sh
./gradlew :core:jvmTest :core:macosArm64Test :cli:linkReleaseExecutableMacosArm64
python3 scripts/check-architecture.py
python3 scripts/check-cli-contract.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
./AndroidTV/gradlew :core:jvmTest testDebugUnitTest lintDebug assembleDebug
./AndroidTV/gradlew connectedDebugAndroidTest
(cd web && bun install --frozen-lockfile && bun run check && bun test && bun run build)
```

Linux 的原生任务为 `:core:linuxX64Test`、`:cli:linkReleaseExecutableLinuxX64` 和 `:cli:linkReleaseExecutableLinuxArm64`。分别在对应架构运行同一 CLI 契约检查。Linux 发布以 Ubuntu 24.04 为基线，运行依赖系统 libcurl 和 CA 证书。

## 发布

PR 和推送到 main 时执行检查，发布由 `workflow_dispatch` 触发。产物为双 ABI Android APK、Web 镜像归档、macOS arm64 和 Linux x86_64 / arm64 CLI，附 SHA-256。发布前在实体 TV 核对遥控器与播放。

Android 使用 `~/.android/debug.keystore` 的 `androiddebugkey`，CI 从 Android 签名 secrets 恢复同一密钥。证书 SHA-256 为 `0d81ab7cb1a16d58d91a953fe62049eb77fc840cc544a297ae1854f54bc73b96`。发布时提升 versionCode，不生成替代密钥。

[Web 部署](development/web.md) · [HLS 与真实来源验收](development/android-tv-sources.md) · [弹幕开发](development/danmu-api.md)
