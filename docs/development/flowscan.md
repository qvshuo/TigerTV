# DDYS FlowScan 静态研究

分析 [FlowScan 1.0.0 发布树](https://github.com/ddysdev/flowscan-/tree/91d917875755a68f04ce4cc5bee3bb10832cf5b6) 的 Manifest 和三个脚本。该树未声明明确许可证，TigerTV 独立实现。

- Manifest V3 在 `document_start` 注入页面脚本。脚本拦截 fetch、Response.text、XHR、hls.js Loader 及 Worker 路径。同步 XHR getter 与异步 fetch 走不同改写路径。
- 媒体清单读取时长、URI、主机及 CUE / SCTE35 / DATERANGE，通过断点分组并综合打分。强信号组才删除，单组主路径保留原文。改写移除分片并整理接缝断点，结果按 URL 缓存。
- 深度探测使用 HEAD，失败后尝试短 Range GET。后台读取 Last-Modified、Content-Length 和 ETag 等，已确认决策使用 Last-Modified，尚未证实另两项参与判断或用 Content-Range 恢复完整长度。静态研究也无法确认所有加密、隐式 IV、独立音轨和直播场景的处理，或推断误删率。

## TigerTV 独立实现

Media3 数据源和 hls.js 实例级 Loader 只检查已有清单响应。使用供应商特有的博彩结构，验证等级见来源验收文档。成对广告标记、广告路径、重复片段和加密变化只提供候选线索。证据不足保留原文。HTTP 头、短时长或断点本身不足以识别博彩插播。生产规则不探测额外分片，不执行全局网络拦截。

改写保留分片原 URI、密钥、原始隐式 IV 和初始化分片，并将媒体字节范围写成原始显式偏移。主清单登记多个视频变体，独立音频及依赖外置音轨的视频保留原样。直播与无法安全解析的清单原样交给播放器。360《逐玉》的 26.4 秒品牌插播与正片同目录、连续编号且沿用 AES-128 状态，无可靠清单边界，也不在当前博彩过滤范围内。

[规则参数](../../shared/hls-heuristics.json) · [通用正反例](../fixtures/hls-smart.sample.json) · [来源正反例](../fixtures/hls-source-groups.sample.json) · [多变体与字节范围](../fixtures/hls-adaptive.sample.json) · [真实样本与验收](android-tv-sources.md)
