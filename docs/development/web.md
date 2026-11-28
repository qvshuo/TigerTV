# Web/PWA 开发与部署

```sh
cd web
bun install --frozen-lockfile
bun run dev
bun run build
bun run start
```

Vite 开发时复用生产元数据处理器。PWA 只缓存应用外壳。离线豆瓣快照用 `bun web/scripts/update-douban.mjs`（仓库根目录）更新。

Web 默认读取仓库手动维护的 [LunaTV-config.json](../../shared/LunaTV-config.json)。修改默认名单后重新构建镜像。`TIGERTV_SITES` 可从名单中按配置键选取子集。

## Compose

从仓库根目录运行：

```sh
docker compose -f web/compose.yaml up -d --build
docker compose -f web/compose.yaml -f web/compose.network.yaml up -d --build
```

第一种默认发布到 `0.0.0.0:18811`，可通过 `--env-file` 使用 `web/.env.example` 中的变量。第二种使用 `TIGERTV_PROXY_NETWORK` 加入现有反代网络，反代访问 `tigertv:8080`，无需发布宿主机端口。域名和 HTTPS 由部署者管理。子目录部署时反代剥离前缀，访问地址保留末尾斜杠。发布归档用 `docker load -i <文件>` 导入后按相同配置用 `up -d --no-build` 启动。

Gateway 限定上游来源、接口及公开豆瓣海报路径。默认同源，`TIGERTV_WEB_ORIGIN` 只允许指定 Origin 的元数据客户端。浏览器直连媒体 CDN 和用户的弹幕服务，由这些上游决定 TLS、授权、CORS 及播放兼容性。原生 HLS 在不能安全拦截清单时保留原流。

Gateway 允许默认配置里电影天堂的固定 HTTP 端点和百度云zy、艾旦的两个固定嵌套端点。搜索和详情参数写入内层 URL，外层及目标域名由可信配置决定。请求禁止跟随重定向，被拒绝的配置键会写入启动日志。

## 播放验证

测量下载首字节、传输、解析和布局应分别记录。渲染时钟验收只证明与播放器时间一致，轨道与剧情的语义对齐仍需核对版本和广告。 [弹幕与时间轴](../contract.md#播放与弹幕)

历史浏览器实播和性能测量见[实验记录](../../experiments/web/playback-verification.md)。
