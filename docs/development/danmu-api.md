# danmu_api 接口

服务由用户独立运行。自定义基址可有令牌路径，追加 API 路径时必须保留此前缀。服务内的作品与剧集 ID 不跨实例复用，来源字段也不代表作品原播平台。评论时间与参考时长以秒计，客户端不重复应用服务端偏移。[上游 danmu_api](https://github.com/huangxd-/danmu_api)

`TIGERTV_DANMU_URL` 可向 `./gradlew :core:jvmTest --tests '*DanmakuLiveTest' --rerun-tasks` 提供本机测试地址。调查完整轨道时关闭服务端全量采样限制。
