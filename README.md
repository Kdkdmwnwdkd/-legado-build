# 阅读Shimmer

<p align="center"><img width="125" height="125" src="docs/icon.svg" alt="阅读Shimmer"></p>

<p align="center"><b>书源自己配，书架自己理，AI 和听书按需开</b><br>一个独立维护、不追上游节奏的 Android 阅读器</p>

应用本身不带书。书源、本地 TXT、EPUB 都由你自己加进来，书架、排版、听书这些功能围绕「日常真的会读」来取舍，不为和谁对齐。

> 本仓库独立维护，不跟踪任何外部项目的更新，也不接收以「同步外部项目」为目的的 PR。代码来源与许可见文末「来源与许可」。

## 现在能做什么

| 方向 | 能力 |
| --- | --- |
| **找书** | 书源搜索、发现、换源、目录更新、章节缓存；书源查重可按分组筛选 |
| **本地书** | TXT 与 EPUB；EPUB 有独立排版引擎，覆盖、滑动、仿真三种翻页，保留书内 XHTML/CSS/图片与链接 |
| **书架** | 列表与网格、分组、自定义标签、筛选、批量管理；详情页沉浸式封面，可写一句备注（如「等完结」） |
| **排版** | 字体字号行距段距、替换净化；普通正文可选原生或 WebView 排版，两套设置分开存 |
| **高亮** | 本地 `.red` 规则，文字样式、背景图案与图片气泡；完整 CSS 效果走 EPUB 渲染 |
| **页面模板** | 首页与续页的 HTML/CSS/JavaScript，支持多正文区、动态页眉页脚、动效与竖排 |
| **听书** | 系统 TTS（含 MultiTTS 等第三方引擎）与网络 TTS；原文跟随、跨应用悬浮窗、定时停止 |
| **AI** | 可配置服务，带书源搜索、书籍与章节读取、阅读记录查询、联网等工具 |
| **自动化** | 定时任务；本地、WebDAV、对象存储备份；DNS 与 DoH 按功能分流 |
| **记录** | 书签即摘录，可按书名/章节/内容搜索并导出 Markdown；阅读记录导出 Markdown 报告或 CSV 每日明细 |

更细的对照在 [功能说明](docs/features.md)。

## 这个分支做了什么

代码基线来自 Legado 的一条分支，但功能取舍是自己的。目前和基线的主要差别：

- **身份独立**：独立应用名与自制图标，和别的分支装在一起也不互相覆盖。
- **更新独立**：只读本仓库的 Release，不认别处的版本。版本号用时间戳（`3.26.MMDDHHmm`），本地构建和 CI 产物能直接比新旧。
- **AI 重做**：设置页按「一条对话主线」组织，聊天首页给可点的建议；新增正文净化（可另存为替换规则）与服务商预设。
- **听书跟随翻页**：手动翻页时朗读跳到当前页，跨章同样生效。
- **段评**：规则解析、摘要缓存、评论栏全链路。
- **备份拆分**：本地与云端分开，云端失败不中断本地。
- **网络**：DNS/DoH 选择、按功能分流、域名例外、服务配置与测速。
- **构建**：签名密钥不入库，Gradle 与 Maven 依赖带镜像回退。

## 下载

[GitHub Releases](https://github.com/Kdkdmwnwdkd/-legado-build/releases) 是唯一发布渠道，应用内更新也读这里。

最低系统要求 **Android 7.0（API 24）**。旧版本曾声明支持 Android 5.0，但主界面依赖的渲染组件从 2025 年起就要求 API 24，低版本实际无法运行，故不再声明。

## 文档

- [功能说明](docs/features.md)
- [页面模板：应用、分享与编写](docs/reader-templates.md)
- [网络与 DNS](docs/doh-network.md)
- [高级标题等视觉资源包](docs/visual-resource-packages.md)
- [段落规则和气泡包导入](docs/online-package-import.md)
- [EPUB 直排引擎](docs/epub-direct-engine.md)
- [Web 与 Content Provider API](api.md)
- [更新日志](CHANGELOG.md)

高亮规则使用本地 `.red` 文件导入。完整图片图案与复杂 CSS 效果使用 EPUB 渲染；普通正文的 EPUB 模式当前不启用段落规则与 `pclick`，原始图片 `click` 和替换净化继续支持。页面模板库提供独立备份。

## 构建

环境：JDK 17、Android SDK（compileSdk 37），Gradle Wrapper 自带，无需本地安装。

```bash
./gradlew :app:assembleAppDebug -Pabi=arm64-v8a     # 调试包，默认 debug 签名
./gradlew :app:testAppDebugUnitTest                 # 单元测试（只编译 debug 一套）
```

> 不要用 `./gradlew test`：它会同时编译 debug 与 release 两套 Kotlin，耗时接近翻倍，CI 用的是上面这条。

正式包需要四个签名参数（库路径、库密码、别名、密钥密码）。仓库里不保存签名库，也不写具体参数，构建时由本机命令行或 CI Secrets 传入；参数清单与签名库生成方式见维护者本地文档。

## 发布（维护者）

自动发布依赖仓库 Secrets（签名库与密码）。这些值不在仓库里，配置方式见维护者本地文档。

工作流：

| 文件 | 用途 |
| --- | --- |
| `ci.yml` | 每次 push / PR：跑单元测试 + 编译 debug 包 |
| `android-fast-release.yml` | 手动触发：编译 arm64-v8a 与 armeabi-v7a 正式包，从 `CHANGELOG.md` 取更新说明并发布 |
| `android-fast-debug.yml` | 手动触发：只更新 `nightly` 预发布标签，不触碰正式版 |

产物命名必须保持 `<任意>_<任意>_<版本>_<版本号>.apk` 且不能含 `release` 字样——应用内更新按这个规则解析版本和 ABI。

## 来源与许可

本项目的代码源自开源阅读（Legado）及其分支。按 GPLv3 的要求，如实说明来源：

- [gedoor/legado](https://github.com/gedoor/legado)：原始项目。
- [Luoyacheng/legado](https://github.com/Luoyacheng/legado)：阅读 Sigma。
- [rimvoid/legado](https://github.com/rimvoid/legado)（原 Rimchars/legado，阅读 Archive）：本项目的代码基线。

以上项目与本项目无从属关系，本项目不代表它们的官方发布，也不再跟随它们更新。

项目使用 Rhino、Jsoup、OkHttp、Glide、Miuix、Paged.js 等开源组件，各组件保留各自许可。项目许可见 [LICENSE](LICENSE)（GPLv3），应用内组件说明见 [开源许可](app/src/main/assets/LICENSE.md)。

本项目同样以 GPLv3 开源。二次分发时请保留上述署名并标明修改。
