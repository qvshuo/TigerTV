# 开发弹幕服务

用户授权的开发机：通过环境配置指定。容器 `tigertv-danmu-api`，仅绑定开发机 LAN 地址 指定的 LAN 地址及端口 `9321`，使用 `unless-stopped` 重启策略，不挂载 Docker socket。该部署供局域网开发使用。

接口基址：通过 `TIGERTV_DANMU_URL` 指定。当前采用上游默认开发 TOKEN，接口可直接使用无前缀路径；未配置为公开互联网服务。

上游：`https://github.com/huangxd-/danmu_api`。部署镜像 `logvar/danmu-api:latest`，本次拉取摘要：`sha256:4c93a0d8146ada6d17e47790aadacb6eabebe3f2ee8b2872fbc4df80c848dbb4`。

已验证：搜索《嫁金钗》返回优酷和 360 结果；优酷条目返回 30 集，第 1 集 episodeId 为 10032。这些标识来自运行中的接口，不应硬编码为客户端匹配规则。

主要接口：

- `GET /api/v2/search/anime?keyword=...`
- `GET /api/v2/bangumi/{animeId}`
- `GET /api/v2/comment/{episodeId}?format=json&duration=true`

容器管理：`ssh "$TIGERTV_DEV_HOST" docker logs --tail 100 tigertv-danmu-api`。不要把服务日志中的上游视频 URL 或签名参数直接提交到仓库。

实际弹幕验证：`GET /api/v2/comment/10032?format=json&duration=true` 返回 18,905 条弹幕及 2888.2 秒参考视频时长。共享 `DanmakuClient` 的可选真实接口测试也返回 2 个作品结果、30 集、18,905 条弹幕、2,888,200 毫秒参考时长。客户端不自动将该时长与资源站的去广告时长等同。

复现 Kotlin 实网验证：

```bash
TIGERTV_DANMU_URL="http://<开发机地址>:9321" ./gradlew :core:jvmTest --tests '*DanmakuLiveTest' --rerun-tasks
```

无该环境变量时实网测试不发请求；普通解析测试无需服务。后续 Android 接入使用明确的作品/剧集选择与经确认的参考时间映射，避免仅凭标题自动认定版本一致。
