# danmu_api 接口

复用 [huangxd-/danmu_api](https://github.com/huangxd-/danmu_api)，服务由用户配置和维护，不内置私有Token或部署信息。Android使用说明见 [弹幕开发说明](android-danmaku.md)。

- `GET /api/v2/search/anime?keyword=...`：作品和弹幕来源；source不是影视首播平台。
- `GET /api/v2/bangumi/{animeId}`：按seasonId和episodeNumber读取对应剧集；animeId与episodeId不可混用或跨服务实例复用。
- `GET /api/v2/comment/{episodeId}?format=json&duration=true`：保留地址中的Token路径。videoDuration单位为秒，评论p包含秒时间、模式和颜色；客户端只绘制mode 1。

服务端已应用的偏移、去重和过滤不在客户端重复执行。DANMU_LIMIT以千条为单位对完整结果采样，0表示不限量；它不是轨道密度控制，来源调查时不启用全量限制。

可选Kotlin实网检查通过环境变量`TIGERTV_DANMU_URL`提供带Token的完整服务基址，运行`:core:jvmTest --tests '*DanmakuLiveTest' --rerun-tasks`；未设置时不请求服务。不要将令牌、原始媒体URL或私有接口日志提交仓库。
