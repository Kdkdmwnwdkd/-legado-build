# 阅读 Archive

[中文](README.md) · [English](English.md)

<p align="center"><img width="125" height="125" src="docs/archive_icon.svg" alt="阅读 Archive"></p>

阅读 Archive 继承自 Lyc 维护的 Legado 分支，在 [Legado](https://github.com/gedoor/legado) 的基础上继续完善正文与 EPUB 阅读、界面主题、听书、AI 和自动任务。

应用不内置书籍内容。你可以自行添加书源，也可以导入本地 TXT、EPUB 书籍。

## 下载与更新

- [GitHub Releases](https://github.com/Kdkdmwnwdkd/-legado-build/releases)：公开版本、安装包和更新说明。
- [Gitee Releases](https://gitee.com/zziji/legado/releases)：国内下载镜像。
- [Gitee 更新通道](https://gitee.com/zziji/legado/releases/tag/latest-arm64-release)：应用更新使用的固定入口。

当前公开版本为第十四版 `3.26.09160852-smooth`，提供 arm64-v8a 安装包。安装包和完整更新说明见上方 Release 页面。

## 本轮更新

第十四版集中完善正文排版与翻页体验，主要包括：

- 普通 TXT、书源正文可选 EPUB 渲染，保留原目录、书签和阅读进度。
- 支持本地 Reeden `.red` 高亮规则，提供搜索、编辑、预览、字体和背景图片选择。
- 自定义首页、续页 HTML、CSS 与 JavaScript，内置「星幕月相」「古籍竖排」两套页面模板。
- 统一管理本地图片与字体素材，供高亮规则、页面模板共用。
- 扩大章节预加载和页面缓存，复用已排版 WebView，改善连续翻页和切章等待。
- 改善正文上下衔接、多行高亮、图片气泡和沉浸详情标签显示。
- 新增网络与 DNS 设置，支持 DoH、功能分流、域名例外和测速。

[查看第十四版完整更新文案](docs/releases/2026-09-17-v14.md) · [更新日志](CHANGELOG.md)

## 功能总览

| 方向 | 主要能力 |
| --- | --- |
| 阅读 | 书源与本地书籍、原生与 EPUB 排版、翻页动画、阅读样式、书签与进度 |
| 书架与详情 | 列表和网格、分组、标签、批量管理、沉浸详情、目录与定时更新 |
| 高亮与模板 | 本地 RED 规则、图案高亮、字体选择、页面 HTML/CSS/JavaScript、横排与竖排 |
| 素材与主题 | 共用图片字体库、日夜主题、背景、高级标题、页眉页脚与气泡 |
| 听书与多媒体 | 系统和网络 TTS、原文跟随、跨应用悬浮控件、漫画与视频入口 |
| AI | 可配置 AI 服务、书源搜索、书籍与章节读取、阅读记录查询及联网工具 |
| 自动化与数据 | 定时任务、缓存、备份恢复、WebDAV、对象存储与容器管理 |
| 网络 | DNS/DoH 选择、按功能分流、域名例外、服务配置与测速 |

[查看详细功能及模式差异](docs/features.md)

## 使用文档

- [页面模板：应用、分享与编写](docs/reader-templates.md)
- [网络与 DNS](docs/doh-network.md)
- [高级标题等视觉资源包](docs/visual-resource-packages.md)
- [段落规则和气泡包导入](docs/online-package-import.md)
- [Web 与 Content Provider API](api.md)
- [上游帮助文档](https://www.yuque.com/legado/wiki)

高亮规则使用本地 `.red` 文件导入。完整图片图案与复杂 CSS 效果使用 EPUB 渲染；普通正文的 EPUB 模式当前不启用段落规则与 `pclick`，原始图片 `click` 和替换净化继续支持。页面模板库提供独立备份。

本版已完成自动化与浏览器检查，尚未完成 Android 真机显示、触控和整机帧率验收。问题反馈请提供版本、渲染模式和复现步骤。

## 开源与致谢

感谢 [gedoor/legado](https://github.com/gedoor/legado)、[Luoyacheng/legado](https://github.com/Luoyacheng/legado) 及上游贡献者。定时任务功能感谢明月的贡献与支持。

项目使用了 Rhino、Jsoup、OkHttp、Glide、Miuix、Paged.js 等开源组件；各组件保留各自许可。项目许可见 [LICENSE](LICENSE)，应用内使用的组件说明见 [开源许可](app/src/main/assets/LICENSE.md)。

历史说明保存在 [2026 年 7 月更新记录](docs/changelog/2026-07.md) 和 [上游历史日志](docs/changelog/upstream-2022.md)。
