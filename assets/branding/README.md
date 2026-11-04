# 品牌图标

`originals/` 保存用户提供的三张原始 PNG，尺寸和内容保持不变。应用及文档使用生成的小尺寸文件。

| 用途 | 母图 | 输出 |
| --- | --- | --- |
| GitHub README | `originals/icon-transparent.png` | `web/logo.png`，256×256，以 128×128 显示 |
| 网站 favicon | `originals/icon-transparent.png` | `web/favicon-16.png`、`favicon-32.png`、`favicon-48.png` 及包含这三种尺寸的 `favicon.ico` |
| macOS 客户端 | `originals/icon-opaque.png` | `macOS/App/Assets.xcassets/AppIcon.appiconset/`，16–1024 像素，覆盖现有 1x/2x 槽位 |
| Android TV 启动图标 | `originals/icon-opaque.png` | 原生自适应图标 `mipmap-anydpi/ic_launcher.xml`；前景位图为 108、162、216、324、432 像素，覆盖 mdpi 至 xxxhdpi |
| Android TV 横排 Banner | `originals/tv-banner.png` | 同一组 mipmap 目录中的 `banner.png`，160×90 至 640×360 |

TV Banner 保持 16:9，使用虎头图案与纯白背景，不叠加文字。macOS 图标和 TV Banner 移除无实际透明像素的 alpha 通道；Android 自适应前景留出透明安全区，由系统施加遮罩；TV 不支持主题图标，因此只在该资源上豁免通用的 monochrome 提示；README 与 favicon 保留透明背景。

## 生成与压缩

在 macOS 上执行：

```sh
uv run --no-project --with pillow python scripts/generate-icons.py
```

使用 [TinyPNG 官方 API](https://tinypng.com/developers/reference) 压缩全部生成的 PNG：

```sh
uv run --no-project --with pillow python scripts/generate-icons.py --tinypng
```

压缩命令从环境变量 `TINYPNG_API_KEY` 读取凭证，缺失时直接报错。不要将 Key 写入代码、命令历史或仓库。相同像素尺寸的重复 PNG 只上传一次；压缩完成后再组装 favicon.ico，保留压缩后的 PNG 数据。原始母图不经过有损压缩。

网站可以选择以下一种 favicon 引用：

```html
<link rel="icon" href="assets/branding/web/favicon.ico">
<link rel="icon" type="image/png" sizes="32x32" href="assets/branding/web/favicon-32.png">
```
