# TigerTV 工程约定

- 维护 Android TV、Web/PWA 和原生 CLI。Android TV 与 CLI 共用 Kotlin Core，Web 使用 Bun + TypeScript。保留已归档的 macOS SwiftUI 和 bridge，默认构建与发布不包含它们。
- 跨端修改遵循 [契约](docs/contract.md) 和 [夹具](docs/fixtures/)。HLS 参数与弹幕匹配规则放在 `shared/`。优先复用现有逻辑，保持取消传播和会话隔离。
- Bun 只提供 Web 和白名单元数据。媒体清单、分片、视频及密钥由客户端直连。Android TV 和 CLI 独立访问原站，弹幕服务独立部署。私有 API 地址、令牌、真实临时媒体 URL 和原始日志不得入库。
- 去广告限打断剧情的博彩插播。生产规则必须跨作品复用，可采用经验证的来源级特征。片名、集数、固定时间点和完整 URI 不得作为生产指纹。按[验收口径](docs/development/android-tv-sources.md#博彩插播验收口径)报告效果。
- Android TV 优先使用原生 TV Material 控件及 D-pad 路径。保留导航返回焦点和播放会话边界。不要修改 Android Studio 或 AVD 的窗口设置，不用 `-no-window` 启动模拟器。通用 APK 同时包含 `armeabi-v7a` 和 `arm64-v8a`。
- 按 [开发与发布](docs/README.md) 执行受影响平台的现有检查。保留依赖升级与工具链诊断，保留用户截图和 IDE 改动。签名只用指定证书，每次发布增加 versionCode。提交、推送、发布及部署需要用户明确授权。
