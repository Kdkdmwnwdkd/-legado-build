# 阅读Shimmer

[中文](README.md) · [English](English.md)

<p align="center"><img width="125" height="125" src="docs/icon.svg" alt="阅读Shimmer"></p>

阅读Shimmer 是一个 Android 阅读器。书源自行导入，也可以读本地 TXT、EPUB。

应用不内置任何书籍内容，不提供内容来源，也不托管任何书籍。

> 本仓库独立维护，不跟踪任何上游项目的更新，也不接收以「同步上游」为目的的 PR。代码来源与许可见文末「来源与许可」。

## 下载与更新

- [GitHub Releases](https://github.com/Kdkdmwnwdkd/-legado-build/releases)：唯一发布渠道，应用内更新同样读取这里。

包名为 `io.legado.app.shimmer`，与 Legado（`io.legado.app`）、阅读 Archive（`io.legado.app.Archive`）**互不覆盖，可同时安装**。

最低系统要求 **Android 7.0（API 24）**。旧版本曾声明支持 Android 5.0，但主界面依赖的渲染组件从 2025 年起就要求 API 24，低版本实际无法运行，故不再声明。

## 从旧版本迁移

包名变更后，旧版本的本地数据不会自动带过来（Android 按包名隔离数据）。迁移方式：

1. 在旧版中打开「备份与恢复」，导出完整备份（`.zip`）。
2. 安装本版，在「备份与恢复」中选择该文件恢复。
3. 确认书源、书架、阅读进度、阅读设置都正常后，再卸载旧版。

> 旧版的「书源分组」「阅读主题」等自定义项也会随备份恢复；WebDAV 备份需要重新填写账号。

## 主要改动

- 独立包名 `io.legado.app.shimmer`，可与 Legado、阅读 Archive 共存。
- 更新只读本仓库的 GitHub Release。
- 版本号统一为时间戳（epoch 分钟），本地构建与 CI 产物可直接比较新旧。
- 签名密钥移出仓库，由 CI Secrets 提供。
- 听书跟随翻页：手动翻页时听书自动跳到当前页，跨章节同样生效。
- 书源查重支持按分组选择。
- 备份区分本地与云端：云端失败不再中断本地备份。
- 水墨风格图标。

完整历史见 [CHANGELOG.md](CHANGELOG.md)。

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

高亮规则使用本地 `.red` 文件导入。完整图片图案与复杂 CSS 效果使用 EPUB 渲染；普通正文的 EPUB 模式当前不启用段落规则与 `pclick`，原始图片 `click` 和替换净化继续支持。页面模板库提供独立备份。

## 构建

环境：JDK 17、Android SDK（compileSdk 37），Gradle Wrapper 自带，无需本地安装。

```bash
./gradlew :app:assembleAppDebug -Pabi=arm64-v8a     # 调试包，默认 debug 签名
./gradlew :app:testAppDebugUnitTest                 # 单元测试（只编译 debug 一套）
```

> 不要用 `./gradlew test`：它会同时编译 debug 与 release 两套 Kotlin，耗时接近翻倍，CI 用的是上面这条。

正式包需要签名参数，仓库内不再保存任何密钥：

```bash
./gradlew :app:assembleAppRelease -Pabi=arm64-v8a \
  -PRELEASE_STORE_FILE=/path/to/shimmer-release.jks \
  -PRELEASE_STORE_PASSWORD=... \
  -PRELEASE_KEY_ALIAS=shimmer-release \
  -PRELEASE_KEY_PASSWORD=...
```

## 发布准备（维护者）

自动发布依赖以下仓库 Secrets，缺任意一个都会在构建前直接失败：

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_B64` | 签名库的 base64：`base64 -w0 shimmer-release.jks` |
| `KEYSTORE_PASSWORD` | 签名库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

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
