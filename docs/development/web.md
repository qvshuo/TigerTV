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

## 可选元数据 Gateway

```sh
cp gateway/.env.example gateway/.env
docker compose --env-file gateway/.env -f gateway/compose.yaml up -d --build
```

默认监听服务器的 `127.0.0.1:18811`，同一容器供应静态 Web 和 `/api/metadata/`。本地部署可访问 `http://127.0.0.1:18811/`。远程验收可使用 SSH 转发，正式 PWA 可放在已有的 HTTPS 入口下。另行托管 Web 时，构建设置 `VITE_TIGERTV_GATEWAY`，并用 `TIGERTV_WEB_ORIGIN` 指定精确的允许 Origin。纯静态构建保持浏览器直连模式。

可用 `TIGERTV_SITES` 从项目配置中选择固定站点键，默认启用所有符合条件的来源。Gateway 从构建时的项目配置建立 HTTPS 上游允许列表，按固定站点键接受推荐、搜索和详情。它拒绝自定义 URL、重定向、未知路由和重复参数，限制上游并发为 20、单次请求为 12 秒、响应为 10 MiB。搜索只传卡片字段，详情包含原始播放链接。媒体清单、分片、MP4 和密钥继续由浏览器直连第三方。Android TV、CLI 和用户配置的弹幕地址继续独立请求。

## 真实来源抽样

2026-11-20 从 MacCMS 当前配置搜索《逐玉》，用静态站点的模拟 Origin 检查响应头，并用浏览器和原生播放器验证实际媒体播放。CORS 需要浏览器可读取整个请求链，服务端返回 HTTP 200 只能说明 HTTP 请求成功。

| 来源 | 元数据 | 清单与资源 |
|---|---|---|
| 360 | 搜索和详情返回 200，均缺少 `Access-Control-Allow-Origin`。Chrome fetch 被拦截 | 主、子清单开放 CORS。分片支持 206，AES-128 密钥开放 CORS。真实浏览器和 Media3 直连解码。复核后的有效时长为 2841.760 秒 |
| 爱奇艺 | 搜索和详情返回 200，缺少 CORS 响应头 | HLS 和样本分片开放 CORS。分片 HTTP 类型为 `image/jpeg`，生产规则以清单内容匹配 |
| 非凡 | 搜索和详情返回 200，缺少 CORS 响应头 | 主、子清单以及 TS 分片开放 CORS |
| 光速、红牛 | 搜索和详情返回 200，缺少 CORS 响应头 | 清单、TS 和 AES-128 密钥开放 CORS。分片 Range 成功 |
| 茅台 | 回显 Origin，但关键词搜索返回 `code=1002`，接口本身禁用关键词 | 本轮未取得测试媒体 |
| 暴风、魔都 | 直连探测曾遇到 TLS 错误或超时，魔都随后通过 Gateway 返回过搜索条目 | 暴风用经过脱敏的真实清单验证，魔都留存清单未命中。本轮未验证两站最新播放接缝 |
| 豆瓣推荐 | 直接请求返回 400，附带官方 Referer 返回 200，仍缺少 CORS 响应头 | Gateway 能读取实时推荐。静态模式显示注明日期的快照，拒绝外站 Referer 的海报以首字兜底 |

当前 360 样本去掉四组共 79.101 秒。爱奇艺留存样本命中 17.567 秒，暴风脱敏清单识别两组各 25.760 秒，魔都留存样本没有命中。各站可用性与清单会变化。纯静态模式下，360 等来源仍缺少元数据 CORS 许可。实网页面经部署的 Gateway 获取元数据，媒体 CDN 请求由浏览器直接发出。 [FlowScan 研究与规则边界](flowscan.md)

## 验收范围

- 测试机通过独立 Compose 项目及回环端口运行 Gateway。已验证真实首页推荐、搜索、详情和媒体路由拒绝。对默认站点列表和四站限定列表分别测试，360 有偶发搜索失败，重试后取得结果。Chrome 从真实豆瓣《嫁金钗》卡片打开该作品，验证播放、退出、续播、自动下一集、画中画和离线外壳。Firefox 与 WebKit 对真实《逐玉》跨越新广告接缝，完成续播和自然结束自动下一集。
- 用户配置的真实弹幕 API 在 Chrome 中通过 CORS 读取，真实作品搜索返回四个候选，首集可自动匹配。Chrome 手动选择真实爱奇艺弹幕轨道，加载约 1.34 万条弹幕，保存选择与一秒偏移并续播。较大的真实轨道约 4 MB，单次完整读取实测约 35 秒，轨道请求预算调整为 50 秒。Android TV 模拟器同时连接真实弹幕 API 和 360 CDN，使用生产匹配器和渲染器验证暂停、Seek、1.5×、偏移以及 960.160 秒接缝后的有效时钟。已有赛季、日期与上下篇匹配行为由共享样本约束。
- Chrome 158.0.8127.37、Firefox 159.0 测试版、WebKit 27.2 使用跨域的 MacCMS 和弹幕夹具测试推荐→搜索→详情→选集→播放→退出→续播→自动下一集，并解码夹具的 H.264/AAC MP4、TS HLS 和 fMP4 HLS。真实默认来源抽样解码了带 AES-128 的 TS HLS，本轮无可复测的默认 fMP4 或 MP4 样本。另验证分集独立进度、跨源倍率、关闭记忆后恢复默认、关闭自动下一集、快捷键、暂停与 Seek 后 Canvas 更新，以及下一集弹幕重新匹配。
- Chrome 夹具注入媒体 404 后，可重试同一集并切换来源。三个浏览器环境解码后均报告官方画中画控件可用，Chrome 已实际进入和退出画中画，Firefox 与 WebKit 验证到入口显示。Chrome 常规配置通过 PWA 可安装性检查并验证离线外壳，系统安装操作未验收。
- WebKit 在禁用 MSE 的测试中退回浏览器原生 HLS。Safari 27.2 的 WebDriver 会话因本机未启用远程自动化而未建立，Safari 应用本身和 iOS 真机尚未验收。
- Chrome 核对桌面和手机宽度、PWA 安装资格及离线外壳。非无痕的临时配置返回零安装资格错误。系统安装流程、实体 Android TV 和 iOS 真机尚未复验。
