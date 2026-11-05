# 品牌图标

`originals/` 保存用户提供的三张原始 PNG，尺寸和内容保持不变。各平台的成品图标已按需生成并随仓库提交，仓库不保留生成脚本。

| 用途 | 母图 | 成品 |
| --- | --- | --- |
| GitHub README | `originals/icon-transparent.png` | `web/logo.png`，256×256，以 128×128 显示 |
| 网站 favicon | `originals/icon-transparent.png` | `web/favicon.ico`，内含 16、32、48 三种尺寸 |
| macOS 客户端 | `originals/icon-opaque.png` | `macOS/App/Assets.xcassets/AppIcon.appiconset/`，16–1024 像素，覆盖现有 1x/2x 槽位 |
| Android TV 启动图标 | `originals/icon-opaque.png` | 原生自适应图标 `mipmap-anydpi/ic_launcher.xml`；前景位图为 108、162、216、324、432 像素，覆盖 mdpi 至 xxxhdpi |
| Android TV 横排 Banner | `originals/tv-banner.png` | 同一组 mipmap 目录中的 `banner.png`，160×90 至 640×360 |

TV Banner 保持 16:9，使用虎头图案与纯白背景，不叠加文字。macOS 图标和 TV Banner 移除无实际透明像素的 alpha 通道；Android 自适应前景留出透明安全区，由系统施加遮罩；TV 不支持主题图标，因此只在该资源上豁免通用的 monochrome 提示；README 与 favicon 保留透明背景。

网站 favicon 引用：

```html
<link rel="icon" href="assets/branding/web/favicon.ico">
```
