# HLS 离线取证

生产实现与验收见[去广告开发说明](../../docs/development/android-tv-sources.md)。本目录的 JSON 是历史脱敏采样和播放记录。

仓库内工具：

- `source_audit.py`：调用 Kotlin Native CLI 的 `search` 和 `fetch`，并进行有界媒体采样。
- `review_window.py`：下载指定片段并提取音频，供离线复核。
- `FilterPlaylist.java`：离线调用真实 Kotlin 规则，没有另一套业务实现。
- `verify_windows.py`：用当前捕获清单生成小窗口，调用 Kotlin 后复用已下载媒体解码。
- `summarize_audit.py`：调用 Kotlin 生成不含 URL 的汇总，并断言保留 URI 顺序不变。

运行 `source_audit.py` 需要本地私有来源配置（包括 `sites-private.json`）及 CLI 可执行文件。运行 `review_window.py` 需要采集得到的私有媒体清单快照、分片地址和媒体数据。私有配置、采集数据、临时媒体链接和媒体文件均不随仓库分发。`verify_windows.py` 和 `summarize_audit.py` 也依赖本地采集快照或复核输出。

复现仓库内清单验证：先运行 `./gradlew :core:jvmTest :core:jvmJar`，将 `FilterPlaylist.java` 编译到临时目录；classpath 包含 Core JVM JAR、对应版本 Kotlin stdlib 及 kotlinx.serialization 的 JVM core、json JAR。`verify_windows.py` 和 `summarize_audit.py` 的 `--help` 可查看本地输入参数。真实清单、临时链接和媒体留在本机，不得提交。
