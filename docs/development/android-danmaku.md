# Android TV 弹幕

`DanmakuState` 按播放会话持有请求，Core 负责匹配与轨道缓存。匹配及手动选择遵循[跨端契约](../contract.md#播放与弹幕)。

Canvas 用 Media3 实际时钟。仅处理当前可见窗口，Seek 后二分定位。暂停和缓冲时停止逐帧刷新，空窗按下一条评论和倍速定时唤醒。按真实字宽和追尾条件分配轨道，拥挤时跳过当前放不下的评论，无需截断全片轨道。

`LiveDanmakuPlaybackTest` 是可选实网验证，需要 `media_url`、`next_media_url`、`media_site`、`work_title` 和 `danmu_url`。验证首帧、暂停、缓冲、Seek、倍速、校准、去广告接缝与切集清理。实际服务和媒体只用本机配置。历史证据包括 [媒体与弹幕联合回归](../../experiments/hls-ad/android-danmaku-playback-2026-11-16.json) 和 [轨道取样](../../experiments/danmaku/survey-2026-11-17.json)；模拟器结果不能替代实体 TV 验收。
