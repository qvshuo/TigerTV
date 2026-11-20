# Web/PWA 开发与兼容性

Web 使用 Svelte 5 Runes、TypeScript、Vite、Tailwind CSS 4 和 Video.js 10 HTML 组件。播放器及 HLS 适配器按需加载，弹幕由独立 Canvas 按媒体时钟绘制。设置、最近播放、分集位置和弹幕手动选择保存在浏览器。Android TV 与 CLI 原地使用 Kotlin Core，Web 遵循同一 [契约](../contract.md)。

## 本地运行与静态部署

```sh
npm --prefix web ci
npm --prefix web run dev
npm --prefix web run check
npm --prefix web test
npm --prefix web run build
npm --prefix web run preview
```

使用静态 HTTPS 服务发布 `web/dist/`。子目录部署时以末尾含 `/` 的地址打开。PWA Service Worker 预缓存静态页面、脚本、样式和图标。离线可打开界面和本机记录。推荐的数据快照在 `web/src/douban-snapshot.json`，可用 `node web/scripts/update-douban.mjs` 更新后重新构建。

默认读取仓库同步的资源站配置。若站点运营者有支持浏览器 CORS 的直连 MacCMS 源，可用构建环境变量 `VITE_TIGERTV_SOURCES=https://example.org/sources.json` 指定优先配置，结构见 [配置夹具](../fixtures/config.sample.json)。API 本身也必须允许该静态站点的 Origin。浏览器忽略已有包装式 `url=` 中转入口。弹幕 API 由用户在设置中指定，需同样支持浏览器直连。配置表单中的地址是示例。

## 真实来源抽样

2026-11-20 从 MacCMS 当前配置搜索《逐玉》，用静态站点的模拟 Origin 检查响应头，再用 Chrome 验证 360 实际媒体播放。CORS 需要浏览器可读取整个请求链，服务端返回 HTTP 200 只能说明 HTTP 请求成功。

| 来源 | 元数据 | 清单与资源 |
|---|---|---|
| 360 | 搜索和详情返回 200，均缺少 `Access-Control-Allow-Origin`。Chrome fetch 被拦截 | 主、子清单开放 CORS。分片支持 206，AES-128 密钥开放 CORS。Chrome 直连解码并命中去广告，过滤后时长 2868.160 秒 |
| 爱奇艺 | 搜索和详情返回 200，缺少 CORS 响应头 | HLS 和样本分片开放 CORS。分片 HTTP 类型为 `image/jpeg`，生产规则以清单内容匹配 |
| 非凡 | 搜索和详情返回 200，缺少 CORS 响应头 | 主、子清单以及 TS 分片开放 CORS |
| 光速、红牛 | 搜索和详情返回 200，缺少 CORS 响应头 | 清单、TS 和 AES-128 密钥开放 CORS。分片 Range 成功 |
| 茅台 | 回显 Origin，但关键词搜索返回 `code=1002`，接口本身禁用关键词 | 本轮未取得测试媒体 |
| 暴风、魔都 | 本轮网络探测遇到 TLS 错误或超时 | 本轮未取得最新样本。规则沿用 Android 已确认素材 |
| 豆瓣推荐 | 直接请求返回 400，附带官方 Referer 返回 200，仍缺少 CORS 响应头 | 首页显示注明日期的豆瓣快照，拒绝外站 Referer 的海报由标题首字兜底 |

从本轮实际清单取样，Web 规则命中 360 三组共 52.701 秒，爱奇艺一组 17.567 秒。非凡、光速、红牛样本原样通过。360 的 Chrome 验证采用测试夹具提供媒体地址，清单、分片和密钥由 Chrome 从真实 CDN 获取。各站可用性与规则命中会随清单变化。 [FlowScan 研究与规则边界](flowscan.md)

本轮未从默认资源配置证实可在浏览器完成真实站点的搜索→详情→播放全流程。静态首版没有 Gateway。若后续采用自托管 Docker 元数据 Gateway，仍需独立评估允许列表、请求超时及响应限额，且让分片、密钥、清单和 MP4 继续由浏览器直连。Gateway 方案仅为后续设计。


## 验收范围

- Chrome 158.0.8127.37、Firefox 159.0 测试版、WebKit 27.2 使用跨域的 MacCMS 和弹幕夹具测试推荐→搜索→详情→选集→播放→退出→续播→自动下一集，并实际解码 H.264/AAC MP4、TS HLS 和 fMP4 HLS。另验证分集独立进度、跨源倍率、关闭记忆后恢复默认、关闭自动下一集、快捷键、暂停与 Seek 后 Canvas 更新，以及下一集弹幕重新匹配。
- WebKit 在禁用 MSE 的测试中退回浏览器原生 HLS。Safari 27.2 的 WebDriver 会话因本机未启用远程自动化而未建立，Safari 应用本身和 iOS 真机尚未验收。
- Chrome 核对桌面与手机宽度渲染和 PWA 离线外壳。真实 CDN 验证单独记录于上表。远程网络抽查不等于默认资源可从浏览器完整检索。
