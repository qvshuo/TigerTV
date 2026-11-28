# HLS 离线取证

生产实现与验收见[去广告开发说明](../../docs/development/android-tv-sources.md)。本目录的 JSON 是历史脱敏采样和播放记录。

离线工具：

- `source_audit.py`：调用 Kotlin Native CLI 的 `search` 和 `fetch`，Python 做有界媒体采样。并发最多 4，允许公网地址及本地代理的 `198.18.0.0/15` 映射地址。限制文本大小、单次请求耗时及重定向次数。私有输出不得提交。
- `review_window.py`：限定片段范围离线下载，单片 8 MiB / 窗口 48 MiB；FFmpeg 提取音频，支持普通显式 IV AES-128，不支持 DRM、MAP、BYTERANGE。WhisperKit 另外调用，不进入产品。
- `FilterPlaylist.java`：离线调用真实 Kotlin 规则，没有另一套业务实现。
- `verify_windows.py`：用当前捕获清单生成小窗口，调用 Kotlin 后复用已下载媒体解码。
- `summarize_audit.py`：调用 Kotlin 生成不含 URL 的汇总，并断言保留 URI 顺序不变。

复现：先运行 `./gradlew :core:jvmTest :core:jvmJar`，将 `FilterPlaylist.java` 编译到临时目录；其 classpath 包含 Core JVM JAR、对应版本 Kotlin stdlib 及该临时目录，再调用上述脚本的 `--help`。`source_audit.py` 需要预先准备仅包含选定来源的配置及 `sites-private.json`，复用现有 `/tmp/tigertv-source-audit/` 可避免重复搜索与下载。必须显式传入 `--sites`，避免意外再次扫描全部配置。
