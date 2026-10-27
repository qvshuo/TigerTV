# TigerTV 跨平台开发与发布

## 架构

当前仅开发 macOS、Android TV 和 macOS 原生 CLI，Windows 版本暂缓。

桌面应用不使用 JVM。macOS 保留 SwiftUI + AVPlayer；Android TV 保留 Compose for TV + Media3。GUI 在进程内调用共享库，不启动 CLI。

- `core`：Kotlin Multiplatform 业务、模型、Ktor 网络、配置、播放解析、Quantumult X、URL 缓存与历史。
- `nativeBridge`：macOS 静态 `TigerTVCore.framework` 和 Swift 调用边界。
- `cli`：macOS arm64 Kotlin/Native 可执行文件。
- `macOS`、`AndroidTV/app`：各自平台的原生 UI 和播放器。
- Python CLI、Swift/Android 旧 API 客户端及其测试暂留作迁移参考；生产 GUI 的仓库入口均使用 `core`。

共享代码不依赖 Android、SwiftUI、AVKit 或播放器引擎。平台播放器实现 `load/play/pause/seek/setVolume/close` 的共同语义；Swift 适配器采用原生接口，不把 UI 对象传入业务 ABI。

## 环境和构建

Kotlin 2.4.21、Gradle 9.6、AGP 9.2.1、JDK 21。JDK 仅用于构建，不会进入桌面发布包。Kotlin/Native 首次构建会下载编译器、LLVM 和平台依赖。

macOS：Apple Silicon、macOS 26+、兼容的 Xcode（包含命令行工具）。Android：SDK 37，设备 Android 14+。

### macOS

```bash
./gradlew :core:jvmTest :core:macosArm64Test :nativeBridge:macosArm64Test
./gradlew :cli:linkReleaseExecutableMacosArm64
xcodebuild -project macOS/TigerTV.xcodeproj -scheme TigerTV \
  -configuration Release -destination 'platform=macOS' \
  -derivedDataPath DerivedData ARCHS=arm64 CODE_SIGNING_ALLOWED=NO build
```

Xcode 的构建阶段自动生成相应 Debug/Release 的静态 framework。应用不需要携带 framework 动态库或 Java。CLI 位于 `cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe`；分发时重命名为 `tigertv`。

Swift 原始业务参考测试：`swift test --package-path macOS`。这些测试不代替共享核心和桥接测试。

### Android TV

```bash
# 设置 ANDROID_HOME，或在仓库根 local.properties 中设置 sdk.dir。
./gradlew -Pandroid=true :androidApp:testDebugUnitTest :androidApp:assembleDebug
# 现有入口仍支持：
cd AndroidTV && ./gradlew test assembleDebug
```

保留 `art.anjing.tigertv` 应用 ID、minSdk 34、v7a/arm64 universal APK。Release 从 2026-10-27 起按用户要求复用 Interlude 的固定签名密钥（本地 `~/.android/debug.keystore`），CI 保留既有 `CI_RELEASE_*` 环境变量并恢复同一份密钥。旧签名的已安装版本不能直接覆盖升级到新签名。`-PreleaseVersion=X.Y.Z` 指定版本名，`-PandroidVersionCode=N` 指定递增版本号（当前默认 12）。每次 Android 发布须递增 versionCode；手动 workflow_dispatch 的 android_version_code 参数用于指定该值。

不带 `-Pandroid=true` 的根构建不需要 Android SDK。

## 桥接协议与生命周期

Swift 使用生成的 `NativeFacade`，由 repository actor 持有并管理生命周期。

请求示例：

```json
{"version":1,"operation":"fetch","site":"🎬站点","vod_id":42}
```

操作：`config`、`search(keyword)`、`fetch(site,vod_id)`、`resolve(url)`、`cover(site,vod_id)`、`history`、`saveHistory(items)`。

响应为 `{"version":1,"data":...}` 或 `{"version":1,"error":"..."}`。搜索、详情的数据字段与 CLI 相同。封面不存在时 data 为 null；保存历史成功时 data 为 true。

- `begin` 立即返回请求 ID，网络在 Kotlin 协程中执行。
- GUI 每 20ms 读取一次 `takeResult`；空结果表示尚未完成。读取到结果后，该请求即被消费。
- `cancel` 取消并释放请求，包括尚未消费的已完成结果。GUI 在结束等待时始终调用它。
- facade 关闭会取消所属任务并关闭网络客户端。释放 facade 后禁止继续调用；Swift actor 隔离调用并在析构时关闭。
- 搜索、详情、播放独立取消；UI 仅接受当前任务结果。窗口关闭时取消等待并释放播放器与 facade。
- 每次播放解析成功都会更新 GUI 的播放修订号，即使 URL 相同也重新装载引擎，避免快速重试被界面状态合并吞掉。TV 错误浮层默认聚焦“重试”。

轮询协议避免原生工作线程在 GUI 已销毁后回调已销毁的界面；不导出内部业务对象布局，不要求 Swift 直接消费 Kotlin suspend 或 Flow 类型。

## CLI、安装与兼容

```bash
tigertv --source ./sources.json search 逐玉
tigertv fetch --site '🎬站点' --vod_id 42
tigertv quanx 逐玉
tigertv logs --full
```

- `--source` 放在子命令前；不读写远程配置缓存。
- search/fetch 输出 UTF-8 JSON，字段与旧 CLI 一致；quanx/logs 输出文本。
- 成功/帮助/管道提前关闭退出 0；业务失败退出 1；参数错误退出 2。错误写 stderr，日志写文件。
- macOS CLI 使用 `/tmp/tigertv-cli-config-cache.json` 和 `/tmp/tigertv-cli.log`。日志时间为 UTC。
- GUI 使用新的 `shared-v1` 子目录；历史和缓存不从旧版本迁移。历史保存在 macOS Application Support / Android filesDir，配置与封面 URL 保存在各自 caches 目录；清理缓存不会清空历史。原 macOS 图片缓存可继续独立使用。
- `skills/scripts/install.sh` 从 GitHub Release 下载原生包并验证 SHA-256；支持 `TIGERTV_VERSION`、`TIGERTV_INSTALL_DIR`，不修改 PATH。
- macOS 安装 `tigertv-cli.py` 兼容链接。没有 Python 转发运行时。
- `uninstall.sh` 删除安装文件，保留缓存。下载剧集继续由 Skill 调用 yt-dlp/ffmpeg。

在含新二进制资产的首个 Release 发布前，新安装脚本会因找不到资产失败；本地验证可直接运行构建产物。不得发布声称可用但缺少对应二进制的 Release。

## 测试与发布

```bash
python3 -m unittest tests.test_tigertv_cli
python3 scripts/check-cli-parity.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
./gradlew :nativeBridge:linkDebugFrameworkMacosArm64
swiftc -parse-as-library -F nativeBridge/build/bin/macosArm64/debugFramework \
  -framework TigerTVCore scripts/check-native-bridge.swift -o build/check-native-bridge
build/check-native-bridge
```

差异测试启动本地 HTTP 服务，不依赖远程资源站。Python 仅用于测试；原生进程执行时 PATH 不含 Python/Java。共同 fixtures 在构建时生成到测试源目录，供 JVM 和 Native 测试使用。

CI 在 PR/main 执行构建和测试；仅 `workflow_dispatch` 发布。发布依赖 macOS、Android 两个任务成功。产物包括 macOS 应用 ZIP、Android universal APK、macOS 独立 CLI 压缩包及各自 `.sha256`。

默认桌面资产未签名，Release 正文明确标注。正式签名可在打包前对 macOS 应用和 CLI 使用已导入钥匙串的 Developer ID 签名，并通过 notarytool 公证，签名后重新计算校验值。不要将私钥或密码写入仓库。Android 后续固定复用 Interlude 密钥；本次按用户要求删除旧 Release 及其附件，保留源码历史。

发布前人工验收：

- macOS：首次启动、搜索、选集、HLS/MP4、暂停/跳转、全屏恢复、系统明暗切换、中文输入、关闭释放。
- Android TV：v7a 和 arm64 真机安装，遥控器全流程、首卡焦点、返回恢复、二次搜索、错误重试及下一集。

自动测试成功不代表实机播放或 GUI 验收已完成。将设备、系统、测试结果记录在 Release 说明；未验收的项目标为未验证。

回滚：本次清理后不再提供旧 Release 下载；后续发布可保留采用同一密钥的前版供回滚。Android 回滚可能受 versionCode 限制，优先使用递增 versionCode 的修复版本，不更换签名密钥。

## 本地播放器验证

可以用 FFmpeg 生成无版权测试画面与声音，再由隔离的本地服务提供 HLS/MP4。FFmpeg 仅是测试工具，不是客户端运行依赖。

```bash
mkdir -p /tmp/tigertv-media
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=24 -f lavfi -i sine=frequency=440:sample_rate=48000 \
  -t 12 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -g 48 -c:a aac \
  -movflags +faststart /tmp/tigertv-media/sample.mp4
ffmpeg -i /tmp/tigertv-media/sample.mp4 -c copy -hls_time 2 -hls_playlist_type vod \
  -hls_segment_filename /tmp/tigertv-media/segment%03d.ts /tmp/tigertv-media/index.m3u8
ffmpeg -i /tmp/tigertv-media/sample.mp4 -frames:v 1 /tmp/tigertv-media/cover.png
python3 scripts/serve-playback-fixtures.py /tmp/tigertv-media
```

另一个终端执行实际 AVPlayer 适配器测试（静音）：

```bash
swiftc -parse-as-library scripts/check-macos-playback.swift macOS/App/PlayerView.swift \
  macOS/App/HTTPClient.swift macOS/App/TigerTVError.swift -o /tmp/check-macos-playback
/tmp/check-macos-playback http://127.0.0.1:18766/index.m3u8 http://127.0.0.1:18766/sample.mp4
```

服务还提供 `/config` 和 MacCMS `/api`，可用于 GUI 搜索、封面 detail 兜底、选集和错误重试验证；仅暴露指定测试目录内的媒体文件。Android 模拟器通过 `10.0.2.2:18766` 访问宿主机服务。验证完停止服务并撤销测试配置。
