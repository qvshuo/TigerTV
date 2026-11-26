# Web/PWA 开发与部署

```sh
cd web
bun install --frozen-lockfile
bun run dev
bun run build
bun run start
```

Vite 开发时复用生产元数据处理器。PWA 只缓存应用外壳。离线豆瓣快照用 `bun web/scripts/update-douban.mjs`（仓库根目录）更新。

## Compose

从仓库根目录运行：

```sh
docker compose -f web/compose.yaml up -d --build
docker compose -f web/compose.yaml -f web/compose.network.yaml up -d --build
```

第一种默认发布到 `0.0.0.0:18811`，可通过 `--env-file` 使用 `web/.env.example` 中的变量。第二种使用 `TIGERTV_PROXY_NETWORK` 加入现有反代网络，反代访问 `tigertv:8080`，无需发布宿主机端口。域名和 HTTPS 由部署者管理。子目录部署时反代剥离前缀，访问地址保留末尾斜杠。发布归档用 `docker load -i <文件>` 导入后按相同配置用 `up -d --no-build` 启动。

Gateway 限定上游来源、接口及公开豆瓣海报路径。默认同源，`TIGERTV_WEB_ORIGIN` 只允许指定 Origin 的元数据客户端。浏览器直连媒体 CDN 和用户的弹幕服务，由这些上游决定 TLS、授权、CORS 及播放兼容性。原生 HLS 在不能安全拦截清单时保留原流。

## 历史验证

Chrome、Firefox ESR 及 WebKit 自动化曾以真实 360 流验证播放和过滤接缝。Chrome 另验证画中画。WebKit 自动化不能代替 Safari 应用或 iOS 真机。默认来源当前状态需重测。

约 4 MB 的真实弹幕轨道历史全程约 15.9 秒，gzip 样本约 9.3 秒，首字节仍需 2.4 秒。测量下载首字节、传输、解析和布局应分别记录。渲染时钟验收只证明与播放器时间一致，轨道与剧情的语义对齐仍需核对版本和广告。 [弹幕与时间轴](../contract.md#播放与弹幕)

2026-11-27 复测《逐玉》真实弹幕轨道，gzip 828,233 字节、42,294 条。轨道请求首字节 2.57 秒，后续传输 5.54 秒，解压和 JSON 解析 38 毫秒。Chrome 回放同一响应在本机完成读取和客户端整理约 67 毫秒，缓存低于计时分辨率。真实 Canvas 720 个窗口样本的脚本绘制 P95 为 0.1 毫秒、最大 13.3 毫秒，不含 GPU 呈现。Chrome 实播《逐玉》跨过过滤接缝，显示真实滚动弹幕，暂停停钟，弹幕关闭再开启复用轨道。切集时暂缓旧手动轨道请求，复验未写入旧会话偏好。
