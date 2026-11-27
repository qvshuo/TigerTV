# danmu_api 接口

服务由用户独立运行。自定义基址可有令牌路径，追加 API 路径时必须保留此前缀。服务内的作品与剧集 ID 不跨实例复用，来源字段也不代表作品原播平台。评论时间与参考时长以秒计，客户端不重复应用服务端偏移。[上游 danmu_api](https://github.com/huangxd-/danmu_api)

`TIGERTV_DANMU_URL` 可向 `./gradlew :core:jvmTest --tests '*DanmakuLiveTest' --rerun-tasks` 提供本机测试地址。调查完整轨道时关闭服务端全量采样限制。

## 本地目录采集

将弹幕服务地址放入本地环境变量 `DANMAKU_API`，然后在仓库根目录运行：

```sh
bun web/scripts/collect-danmaku.ts <Gateway地址> /tmp/tigertv-danmaku-corpus
```

再次使用同一输出目录可续采。失败记录写入 `failures.jsonl`。缓存只存目录字段和集名，媒体 URL 与弹幕内容不落盘。代表样本见 [真实目录夹具](../fixtures/danmaku-catalog-capture.sample.json)。

弹幕 API 的 `episodeNumber` 有时是列表序号。集名明确写出集数或期数时优先使用集名。`seasonId` 通过 `seasons[].id` 关联季名。作品标题明确给出季数时，单个名为 `Season 1` 的分组沿用作品季数。

目录采集中，《后会友期》搜索返回 4 个作品记录，4 个选集接口均返回 404，重试结果相同。
