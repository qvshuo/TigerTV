# 数据与交互契约

以 [MacCMS10 API V2](https://github.com/magicblack/maccms10/blob/master/说明文档/API接口说明V2.txt) 为上游协议。业务规则仅在 Kotlin 核心实现；Swift 模型严格解码当前输出，不兼容旧参数、扁平结果或 Python 核心。

## 命令与模型

```bash
tigertv search --query 嫁金钗
tigertv fetch --site '🎬站点完整名' --video-id 42
tigertv --config sources.json search --query 嫁金钗
tigertv quanx --query 嫁金钗
tigertv logs [--full] [--clear]
```

`--config` 在命令前，使用本地文件并绕过远程配置及配置缓存。`--site` 是完整资源名称，`--video-id` 是所选资源的内容 ID；核心映射到上游 `ids`。search/fetch 输出 JSON，quanx/logs 输出文本；错误写 stderr，退出码为成功 0、业务错误 1、参数错误 2。

| 输出 | 结构 |
|---|---|
| 搜索 | `{query, works:[{id,title,sources,playback?,last_played_site?,recent_played_site?}]}` |
| 资源 | `{site,vod_id,vod_name,vod_time,vod_remarks,vod_pic,vod_year,type_name}` |
| 详情 | `{site,vod_id,vod_name,vod_pic,vod_year,type_name,vod_remarks,vod_content,vod_actor,vod_director,vod_area,vod_lang,play_sources,download_sources}` |
| 线路 | `{id,name,episodes:[{name,url}]}` |
| 播放书签 | `{site,line,episode}` |

完整样例在 [fixtures](fixtures/)。详情的两类线路数组必须存在，包括空数组；简介转为纯文本，其余缺失资料规范化为空字符串。作品 ID 不透明，客户端不得重建；资源 ID 保留站点差异。

聚合仅使用去空白、小写标题与去首尾空白年份；年份 `0` 视为未知。空标题按资源身份隔离。同一站点/ID 去重，不使用分类、演员、外部 ID、模糊匹配或评分。所有源并发搜索；站点完成即发布快照，新作品追加，同名匹配并入原卡片，卡片不移动；卡片内资源仍按配置顺序。CLI 只输出最终结果。

## 上游请求

- 搜索：`ac=detail&wd=…&pg=…&pagesize=100`，只解码轻量卡片字段，跳过简介与播放字段；缺少 pagecount 视为一页，空页终止，最多 100 页。
- 详情：`ac=detail&ids=…`，响应必须包含所请求 ID。
- QuanX：先 `ac=list&wd=…&pagesize=1`，再取首项详情及各线路首集。
- code 必须表示整数 1；接受数值字符串与精确整数浮点，拒绝小数、布尔、null、溢出；内容 ID 必须为正数。
- 保留无关查询参数，替换当前操作参数，支持 `url=` 包装代理。Unicode URL 编码保留已有转义与加号。
- 原始线路按 `$$$` 分组，剧集按 `#` 分项，首个 `$` 分名称和 URL；先对齐 from 名称，再过滤空分组。线路 ID 是原始零起始位置。
- 单源搜索/QuanX 失败记录后继续；取消必须向上传播。API/config/HTML/HLS 响应上限 10MB，搜索与详情超时 20 秒。

播放支持直接 m3u8/mp4、原始 HLS 和 HTML 中绝对、相对、转义及编码链接，保留签名查询。QuanX 包含 KEY/MEDIA 域，递归深度二、每个 master 最多三个子项，截断结果不缓存。403 不等于 UA 问题，不增加未经验证的请求头兜底。

所有产品 HTTP 客户端统一使用：

```text
Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15
```

## 配置与持久化

配置保留名称含 `🎬`、没有 `_comment` 键且 API 非空的项；相同 API URL 取首项。读取优先级：显式文件 → 一日内缓存 → CDN（10 秒）→ RAW（5 秒）→ 过期缓存 → 错误。原始配置原子写入，读取时过滤。

GUI 使用 `shared-v1`；历史放 macOS Application Support／Android filesDir，不随缓存清除。搜索历史最多 20 项；每部作品只保存一个最新实际播放书签及一个全局最近资源，不在浏览或聚焦时写入。

缓存：GUI 搜索十分钟，最多五个查询／一万条资源；CLI 不缓存搜索。封面兜底 URL 七天、并发四、不缓存失败；图片缓存七天，macOS 内存上限 64MB／256 张，TV 磁盘上限 64MB。TV 预取最多 24 部作品，每批六张；离开结果页取消所属任务。

默认详情资源：本作品上次播放 → 全局最近播放 → 配置中首个有图资源 → 首个资源，跳过已不存在的源。聚合封面按本作品上次、全局最近、配置顺序尝试，跳过空图和失败图；默认播放源无图不影响使用其他源的封面。

## 双端交互

搜索 → 聚合卡片 → 详情 → 播放器。详情依次展示当前资源封面与资料、资源、线路、选集，栏目不写数量。资料来自年份/分类/地区，语言与更新状态另列；导演、主演、简介用相同标签样式，简介始终展开。详情封面只使用当前资源，不能借用其他源图片。

切换资源或线路取消待处理播放、清空选集状态，不自动播放；重试和下一集限于当前线路。播放按钮恢复所选资源中上次播放的线路与集名，无匹配用当前线路第一集。返回按播放器 → 详情 → 结果 → 首页逐级进行。

搜索按钮尺寸固定，搜索时箭头换转圈并禁用操作；macOS 使用白灰系统动效。TV 首次进入详情聚焦播放按钮，下移优先最近播放的资源、线路、集；先滚动创建屏外控件，聚焦播放时回到顶部。TV 控件与播放按钮保持 56dp，按钮状态不改变形状，结果每行五张，卡片焦点放大 1.04；保留原生平台材质与输入方式。

Swift 经版本 1 JSON 请求调用 NativeFacade：`config`、`search(query)`、`fetch(site,video_id)`、`resolve(url)`、`cover(site,video_id)`、`history`、`saveHistory(items)`、`recordPlayback(work_id,site,line,episode)`。响应为 `{version:1,data:…}` 或 `{version:1,error:…}`；Swift 轮询最新增量快照，最终结果消费或取消时释放任务。Android 直接调用同一 CoreService。双端必须丢弃已取消或被替代操作的迟到结果。
