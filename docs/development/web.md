# Web/PWA 开发与兼容性

`web/` 是 Bun + TypeScript 工程。Svelte 5 Runes、Vite 和 Tailwind CSS 4 构建静态 SPA。Video.js 10 与 HLS 适配器按需加载，弹幕由 Canvas 按媒体时钟绘制。Bun 同时提供应用和 `/api/metadata/`。Android TV 和 CLI 独立请求上游。 [数据与交互契约](../contract.md)

## 本地开发

```sh
cd web
bun install --frozen-lockfile
bun run dev
bun run check
bun test
bun run build
bun run start
```

使用 Bun 1.4.4。Vite 开发服务器挂载同一元数据处理器，生产入口是 `web/server/index.ts`。生产默认监听 8080。PWA 只预缓存应用外壳，离线可查看本机记录。豆瓣实时集合不可用时显示带日期的快照，运行 `bun web/scripts/update-douban.mjs` 可更新快照。

## Docker Compose

从仓库根目录运行：

```sh
docker compose -f web/compose.yaml up -d --build
```

默认发布到 `0.0.0.0:18811`。可复制 `web/.env.example`，用 `--env-file` 指定后修改端口、监听地址和固定来源。Linux x86_64 的发布归档可用 `docker load -i TigerTV-Web-Linux-x86_64.tar.gz` 导入，再用 Compose `up -d --no-build` 启动。

有共享 Docker 网络的反向代理可直接访问 `tigertv:8080`。将 `TIGERTV_PROXY_NETWORK` 设置为代理已加入的网络名称，运行：

```sh
docker compose -f web/compose.yaml -f web/compose.network.yaml up -d --build
```

网络模式省略宿主机端口映射。HTTPS 和域名在用户的反代上配置。子目录发布时，反代剥离路径前缀，浏览器以带末尾斜杠的地址打开应用。

Gateway 按固定源键提供配置、搜索、详情和豆瓣推荐。豆瓣封面另有仅限固定 CDN 和路径的公开 JPEG 接口，响应最多 2 MiB。它拒绝自定义目标、上游重定向、非法参数和重复参数，限制上游并发 20、单请求 12 秒、响应 10 MiB。默认同源访问。API 客户端确需跨源时，可通过 `TIGERTV_WEB_ORIGIN` 允许一个精确 Origin。服务器详情响应包含第三方播放地址。清单、分片、MP4 和密钥由浏览器直接从 CDN 加载。弹幕服务单独运行，用户在设置中填写支持 CORS 的 API 地址。

## 兼容性

真实来源有独立的 TLS、CORS、清单、分片和授权限制。360 和爱奇艺的搜索或详情曾缺少浏览器所需的 CORS 头，经 Gateway 可访问。部分默认来源出现上游失败，非凡部分媒体返回 403。验证到的 CDN 样本包含 AES-128 TS，客户端按浏览器能力选择 hls.js 或原生 HLS。 [去广告覆盖与真实样本](flowscan.md)

## 弹幕性能

4,035,786 字节、42,298 条弹幕的真实轨道，未压缩首字节 3.46 秒、总耗时 15.89 秒；gzip 样本首字节 2.40 秒、总耗时 9.34 秒，传输 828,219 字节。首字节包含上游处理和连接往返，客户端无法进一步拆分。历史约 35 秒的请求受网络和服务状态影响，本轮未复现同一耗时。

Chrome 使用同一真实响应体隔离测试，移除大轨道 JSON reviver 后，纯解析中位数从约 39 毫秒降到 3.8 毫秒。Canvas 布局分帧后，本机最长单帧测得约 34 毫秒，整轨布局约 167 毫秒，保留开头、中段和结尾的绘制。两端在内存中按服务地址和剧集保存最近三条完整轨道，十分钟过期。Chrome 实际退出并续播时，缓存轨道约 36–39 毫秒可用，未再次发起轨道下载。Android TV 绘制滚动弹幕，Web 另绘制固定顶底弹幕。

Chrome 实播 360《逐玉》，用选中的真实弹幕轨道核对有效播放时间 56、1213、2728 秒附近的 81 个已绘制弹幕位置。计入手动偏移后，样本中播放器与 Canvas 换算的时钟误差小于 1 毫秒。该测试检查渲染时钟，剧情和弹幕语义是否一致仍取决于素材版本和广告残留。

## Bun 部署复验

右上角的搜索图标展开输入和历史，提交后显示搜索结果。首页豆瓣各分类按上游 `total` 自动读取全部可用页，每页 36 条，按条目 ID 去重。`/api/metadata/hot/<category>?start=<offset>` 返回当页和总条目数。

- 独立测试容器在 `192.168.5.118:18817` 提供首页和元数据，默认配置包含 25 个源键，豆瓣返回 12 条推荐。真实 360 搜索和详情通过。M3U8、TS、fMP4、MP4 和密钥路径在 Gateway 均返回 404。另用共享网络启动单服务，确认无宿主机端口绑定，网络内可访问 `tigertv:8080`。
- Chrome 158 和 WebKit 27.2 实播 360，完成推荐、搜索、详情、选集、去广告接缝、退出、续播、自动下一集和离线外壳。Firefox ESR 153.7 实测搜索、360 播放、去广告接缝、退出、续播和自动下一集。Chrome 实际进入和退出画中画。Chrome、WebKit 的夹具回归另覆盖快捷键、全屏、倍速、音量和 TS、MP4、fMP4 播放。Chrome 媒体 404 夹具的同集重试与切源通过。
- Android TV 模拟器 40 项常规仪器测试通过。另通过两项真实 360 与弹幕用例，覆盖过滤接缝、暂停、倍速、Seek、缓冲及切集清除旧轨，已校准时的渲染时钟与播放器误差断言为 200 毫秒以内。红牛真实清单原样播放验证通过。macOS arm64 Kotlin/Native CLI 构建与契约测试通过，独立搜索 360 并取得 40 集链接，生成 6 条 Quantumult X 输出并读取日志。
- Safari 应用的远程自动化未开启。iOS、Android TV 实体设备和 Linux 原生 CLI 本轮未复验。内容版本差异和未识别广告会影响语义对齐，技术时钟一致无法消除这些偏移。
