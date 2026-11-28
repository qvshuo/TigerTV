# HLS 离线取证

生产实现与验收见[去广告开发说明](../../docs/development/android-tv-sources.md)。本目录的 JSON 是历史脱敏采样和播放记录。

仓库内可复现的离线工具：

- `FilterPlaylist.java`：离线调用真实 Kotlin 规则，没有另一套业务实现。
- `verify_windows.py`：用当前捕获清单生成小窗口，调用 Kotlin 后复用已下载媒体解码。
- `summarize_audit.py`：调用 Kotlin 生成不含 URL 的汇总，并断言保留 URI 顺序不变。

历史采集和媒体复核使用过 `source_audit.py`、`review_window.py`。它们是未跟踪的本地工具，不随仓库分发。`verify_windows.py` 和 `summarize_audit.py` 虽在仓库中，但需要本地采集快照及复核输出，这些输入不包含在仓库里；仅凭仓库内容无法重跑历史来源采集或媒体复核。

复现仓库内清单验证：先运行 `./gradlew :core:jvmTest :core:jvmJar`，将 `FilterPlaylist.java` 编译到临时目录；classpath 包含 Core JVM JAR、对应版本 Kotlin stdlib 及 kotlinx.serialization 的 JVM core、json JAR。`verify_windows.py` 和 `summarize_audit.py` 的 `--help` 可查看本地输入参数。真实清单、临时链接和媒体留在本机，不得提交。
