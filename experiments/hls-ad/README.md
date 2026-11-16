# Android TV HLS 去广告

当前结果见 [十源验证报告](top10-report.md) 和 [脱敏逐清单数据](top10-audit-2026-11-16.json)。基于 main `7a4db28dc416447644c79027e37ac7aeef2de310`，本轮只推进 Android TV，未改 macOS。早期阶段记录保留在 `android-source-study.md`，其中“360 尚不支持”等状态已由本轮报告更新。

生产链路是 `Media3Controller → HlsAdDataSource → Core`。五个来源的已确认素材规则接入同一次 M3U8 HTTP 响应：暴风、爱奇艺、360、魔都资源、魔都动漫。媒体仍直连 CDN，没有视频代理、转码或实时语音识别。多个来源共享同一个解析器、匹配器和 `MediaTimeline`。

规则只匹配完整 URI 文本指纹向量、每片精确时长、来源及组边界。它们是非加密 FNV64 **URI 文本**指纹，不是媒体内容哈希；未知素材或 URI 改变不删除。同 URI 被替换成等时长剧情仍是风险，不能将该策略称为普适内容识别。剧情烧录广告不删。

过滤仅支持当前真实样本需要的完整 VOD、媒体序号 0、普通 TS、NONE 与显式 IV 的 identity AES-128。保持每个保留分片的密钥状态和必要 DISCONTINUITY。隐式 IV、MAP、BYTERANGE、独立音轨、多变体、直播或未知标签不编辑。单变体主清单保持原文，由 Media3 正常请求唯一子清单后再匹配，无额外预取。失败回退原始清单。

离线工具：

- `source_audit.py`：仅调用既有 Kotlin Native CLI 完成搜索/详情；Python 做有界媒体采样。并发最多 4、只允许公网或已授权 Quantumult X 地址；文本 10 MiB、单请求时间预算、重定向上限。私有输出不得提交。
- `review_window.py`：限定片段范围离线下载，单片 8 MiB / 窗口 48 MiB；FFmpeg 提取音频，支持普通显式 IV AES-128，不支持 DRM、MAP、BYTERANGE。WhisperKit 另外调用，不进入产品。
- `FilterPlaylist.java`：离线调用真实 Kotlin 规则，没有另一套业务实现。
- `verify_windows.py`：用当前捕获清单生成小窗口，调用 Kotlin 后复用已下载媒体解码。
- `summarize_audit.py`：调用 Kotlin 生成不含 URL 的汇总，并断言保留 URI 顺序不变。

复现：先运行 `./gradlew :core:jvmTest :core:jvmJar`，将 `FilterPlaylist.java` 编译到临时目录；其 classpath 包含 Core JVM JAR、对应版本 Kotlin stdlib 及该临时目录，再调用上述脚本的 `--help`。`source_audit.py` 需要预先准备仅包含选定来源的配置及 `sites-private.json`，复用现有 `/tmp/tigertv-source-audit/` 可避免重复搜索与下载。必须显式传入 `--sites`，避免意外再次扫描全部配置。

Android 回归：`./gradlew -p AndroidTV testDebugUnitTest assembleDebug`。`HlsAdDataSourceTest` 运行真实 Android DataSource 测试；`LiveHlsPlaybackTest` 仅显式传入 instrumentation 参数 `media_url` 与 `media_site` 时访问真实 CDN，否则跳过。它从当前响应的时间轴选择所有非尾部接缝，验证音视频输出、进度、暂停和倍速，不能代替人工听感或电视真机测试。敏感参数写临时文件或通过结构化进程参数传入，不公开带令牌 URL。

调试 `SeekProbeActivity` 复用生产控制器。测试使用临时 `.adprobe` 包名隔离既有应用；该构建修改已恢复，没有改变产品 applicationId。
