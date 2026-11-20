# DDYS FlowScan 静态研究

检查 FlowScan 1.0.0 公开发布树 `91d917875755a68f04ce4cc5bee3bb10832cf5b6`。研究只读取 `manifest.json`、`content.min.js`、`background.min.js`、`inject.min.js`，在临时目录格式化并还原字符串表。网站只用于确认目标场景。该发布树未声明明确的开源许可证，TigerTV 仓库未引入其脚本或反混淆产物。 [FlowScan 发布树](https://github.com/ddysdev/flowscan-/tree/91d917875755a68f04ce4cc5bee3bb10832cf5b6) · [DDYS](https://ddys.io/)

| 已证实的机制 | 静态证据 |
|---|---|
| 注入与拦截 | Manifest V3 在 DDYS 页面及匹配 iframe 的 `document_start` 注入 content script，后者加载页面级脚本并转接深度探测消息。页面脚本包含 `fetch`、`Response.text`、XHR `open` 与响应 getter 的拦截，以及 hls.js Loader 和 Worker 路径。异步 fetch 可替换响应正文，XHR 响应 getter 有同步处理路径。Worker 钩子可将命中的文本发回主线程，原 fetch 响应仍返回。 |
| 清单识别 | 主清单跳过。媒体分片读取 `EXTINF`、相对 URI 解析后的地址、文件名、主机变化、`DISCONTINUITY`，并记录 SCTE35/CUE 及广告型 `DATERANGE` 标记。把断点分组，再将短组、时长分布、路径及主机等线索合成分数。只有归为强广告的组进入删除集合。仅一个分组的主处理路径保留原文。 |
| 深度探测 | 后台批量向部分可疑组与正常组样本发 HEAD，非成功响应再试 `GET Range: bytes=0-1`。返回 Last-Modified、Content-Length、ETag、Server 和 Date。已追到的决策读取并比较 Last-Modified。读取 Content-Length 和 ETag 本身不足以证明其参与分类，也未确认 Range 分支用 Content-Range 恢复完整对象字节数。 |
| 改写与缓存 | 跳过目标分片的 EXTINF 与 URI，处理拼接点的 DISCONTINUITY 并合并重复标记，清理清单首尾多余断点。有按清单 URL 的结果缓存。静态检查未确认它为所有加密、隐式 IV、独立音轨、滑窗或媒体序列号场景重建清单状态，也无法推断线上首帧开销或全站误删率。 |

TigerTV 借鉴对清单结构、候选边界和分段结果的显式处理。Android TV 的 Media3 DataSource 与 Web 的 hls.js 实例级 `pLoader` 消费同一个 [规则定义](../../shared/hls-ad-rules.json)。匹配依赖站点、完整 URI 指纹数组、每片精确时长和断点边界。过滤沿用请求自身的响应与 URL，只替换清单文本，浏览器按原地址请求分片和密钥。整条路径省去额外 HEAD、Range、清单预取和全局网络钩子。见 [Video.js 10 HLS 适配](https://videojs.org/docs/framework/html/reference/components/hlsjs-video)。

仅改写已知的完整 VOD，支持明文及显式 IV 的 identity AES-128。保留有效密钥变化、分片 URI、零起点序列号和必要断点。清单压缩后的有效时间映射回原始进度，弹幕使用播放时钟的有效时间。相对地址由 HLS 适配器按最终响应 URL 解析。独立音轨、多变体、直播、隐式 IV、BYTERANGE、MAP、DRM、未知标签和未知素材均保留。魔都规则只限魔都资源。共用样本断言输出清单、原始过滤区间和加密状态。 [共享夹具](../fixtures/hls-filter.sample.json)

360《逐玉》第 1 集的历史样本已实地抽帧确认仍有约 26.40 秒插播，位于已过滤时间约 960.16–986.56 秒。插播和相邻剧情同目录、连续编号、连续时间戳、采用同一 AES-128 结构且没有断点。组结构和 HTTP 头无法独立区分这里的商业段与剧情。保留这一段。若未来再补规则，需要可复核的明确素材证据和跨样本边界。 [Android 真实来源抽查](android-tv-sources.md)
