# Direct EPUB 嵌入式页眉/页脚实施计划

状态：阶段 0—8 已完成各自的代码/契约验证并已建立独立本地提交；真实设备 EPUB 回归仍待执行。

本计划本身的执行版更新单独提交，不与功能代码混合。后续每个阶段只有在本阶段测试通过后才创建本地提交；如果某阶段暴露出前一阶段设计错误，先用独立修正提交封闭问题，再继续下一阶段，绝不把多阶段问题揉进一个大提交。

本计划针对 Direct EPUB 的横向可重排分页。目标是让阅读器页眉/页脚成为 WebView 文档中随书页移动的页面内容，正文为它们让出真实排版空间；不再使用 Native 覆盖层、`position: fixed` 或 `position: sticky`。

## 一、基线与范围

实际源码基线：`0fc81addc fix(epub): keep chapter boundary identity stable`。

`21b7a6fcd chore(epub): checkpoint before embedded reader chrome` 是随后创建的空时间标记，
与 `0fc81addc` 的源码树完全相同，不包含额外代码，也不保存工作区未提交修改。它只用于标记本轮设计开始时间；需要回滚源码时应以 `0fc81addc` 为基准。

当前计划文档的初稿提交为：`c0b0c04e1 docs(epub): plan embedded reader chrome implementation`。
本计划的执行版更新始终单独提交；计划文档提交不改变功能代码。

本轮只处理：

- Direct EPUB 横向、可重排正文的阅读器页眉/页脚；
- 正文真实让位、页面快照、相邻页帧和章节边界的同步；
- 经典文本信息栏（书名、章节名、页码、进度、时间、电量）。

本轮不把以下内容强行塞进同一机制：

- 固定布局、全页插图、视频、音频、交互页面；
- 出版方自行控制页面的复杂 CSS；
- 纵向连续滚动模式的“每屏重复页眉”；
- 需要 Native Lottie 承载的高级页眉/页脚。

这些类型必须明确关闭阅读器页眉/页脚，不能悄悄退回悬浮覆盖层。

## 二、最终渲染模型

每个 Direct WebView 只维护一个当前页面框架。框架属于同一份 HTML 文档，随文档坐标移动，并由 WebView 与正文一起被截图：

```text
页面视口
┌──────────────────────────┐
│ reader chrome header     │
├──────────────────────────┤
│ reader top padding       │
│                          │
│ EPUB 正文多栏分页区域     │
│                          │
│ reader bottom padding    │
├──────────────────────────┤
│ reader chrome footer     │
└──────────────────────────┘
```

实现约束：

1. 页眉、正文、页脚都由同一个 WebView 绘制；Native `FrameLayout` 不新增阅读器页眉/页脚 View。
2. 框架节点使用文档坐标中的页面定位（`absolute`），不使用 `fixed`/`sticky`，因此它随页面滚动、切页和章节 WebView 一起移动，而不是悬浮在视口上。
3. 正文的 `padding-top`/`padding-bottom` 在分页前就包含页眉/页脚高度，页眉/页脚没有任何覆盖正文的机会。
4. 框架背景始终透明；EPUB 自带背景或阅读器主题背景只绘制一次，由同一 WebView/快照链保留。
5. Runtime 用专用 `data-legado-runtime` 标记排除框架文字和矩形，不让它们进入正文页数、锚点和 renderable-content 计算。
6. 当前页、目标页和章节边界页各自先设置正确的框架数据，再生成/提交快照；Native 翻页动画不再补画第二层页眉/页脚。

“嵌入”指它属于书页的 Web 文档和快照，不是把一个 View 盖在 WebView 上。使用文档坐标页面框架是因为当前引擎采用 CSS 多栏分页，CSS 多栏没有可靠的 Android WebView `running headers` 能力；为每个字符流硬切成独立 DOM 页面会重写现有分页引擎，复杂度和回归风险都更高。

## 三、配置与状态模型

新增独立的 `EpubReaderChromeConfig`（名称可在实现时调整），至少包含：

- `enabled`、`headerEnabled`、`footerEnabled`；
- `hideHeaderOnChapterFirstPage`（默认 true）；
- 固定的 `headerHeightPx`、`footerHeightPx`；
- 左/中/右三个插槽及其模式；
- 字体、字号、颜色、分隔线颜色和透明度；
- `geometryRevision`：改变尺寸或开关时递增；
- `contentRevision`：只改变文字时递增。

运行时数据单独使用 `EpubReaderChromeData`：

- 书名、章节名；
- 当前页/总页数；
- 总进度；
- 时间、电量；
- 当前章节页是否为第一页。

几何状态和内容状态必须分离：

- 几何变化会重新分页、清理邻页帧和快照；
- 页码、时间、电量变化只更新固定尺寸节点，不触发正文重新分页；
- 页眉/页脚更新绝不能成为章节激活或翻页成功的额外闸门。

## 四、适用性策略

增加纯 Kotlin 的 `EpubDirectReaderChromePolicy`，先用表驱动测试锁定规则：

| EPUB 类型 | 阅读器页眉/页脚 |
|---|---|
| `REFLOWABLE`、无出版方全页背景 | 启用 |
| `REFLOWABLE`、检测到出版方页面背景 | 启用透明页眉/页脚并保留 EPUB 自身背景 |
| `PUBLISHER_STYLED` | 默认关闭，后续单独评估 |
| `FIXED` | 关闭 |
| `MEDIA` | 关闭 |
| `INTERACTIVE` | 关闭 |
| 全页 artwork、Duokan gallery、scripted canvas | 关闭 |
| 纵向连续滚动 | 关闭（本轮不伪造每屏页眉） |

关闭时必须保持现有 EPUB 渲染路径完全不变。EPUB 自带 `<header>`/`<footer>` 是出版物内容，不与阅读器 chrome 混淆，也不删除。

## 五、分阶段实施与提交规则

每个阶段都遵守同一规则：先修改该阶段文件，运行该阶段测试；测试通过后只按路径暂存相关文件，创建一个独立本地提交。禁止 `git add -A`，禁止把现有无关工作区修改带入提交。

执行状态和提交链：

| 阶段 | 状态 | 本地提交 |
|---|---|---|
| 0 基线 | 已完成 | `21b7a6fcd`（空时间标记；源码基线为 `0fc81addc`） |
| 1 模型与策略 | 已完成 | `f8b25dc07` |
| 2 正文让位 | 已完成 | `dcc725d27`、`43d86f368` |
| 3 WebView 页面框架 | 已完成 | `802bd2b91` |
| 4 Native 桥接 | 已完成 | `dbf5bde9f feat(epub): bridge reader chrome data without reflow` |
| 5 页面帧同步 | 已完成 | `76b384565 fix(epub): include reader chrome in page frame identity` |
| 6 背景/选择/动画回归 | 已完成（契约与实现验证通过） | `973a504b6` `fix(epub): keep reader chrome transparent and selection safe` |
| 7 高级模式边界 | 已完成（契约与编译验证通过） | `8709cbd4d` `feat(epub): define web-rendered advanced chrome boundary` |
| 8 定向测试与收尾 | 已完成（JVM/runtime 回归通过） | `d023faede` `test(epub): cover embedded reader chrome regressions` |

### 本轮实际执行顺序（每一步单独验证、单独提交）

本轮不构建 APK、不上传 R2、不发布 Release。每一步都先完成代码审查和最小定向测试，
再只暂存该步涉及的明确路径并创建本地提交；测试失败或发现设计问题时，停止在当前步，
先创建独立修正提交，不把问题带入下一步。

1. **计划状态校正**
   - 已更新本计划的阶段状态、实际提交哈希、文件边界和验收门槛。
   - 实际提交：`79b92a6a8 docs(epub): align embedded reader chrome execution plan`。
2. **阶段 6：透明背景、选择锁定和动画完整帧**
   - 先审查现有 runtime、页面帧和动画 overlay 的真实调用链，再决定是否需要代码改动；
     没有证据的问题不修改。
   - 只修复会导致不透明覆盖、系统选择菜单、选择期间翻页、反向半页/残影或取消动画恢复
     不完整帧的最小问题。
   - 实际提交：`973a504b6 fix(epub): keep reader chrome transparent and selection safe`。
3. **阶段 7：高级页眉/页脚边界**
   - 只在 EPUB 配置/菜单和纯策略层声明高级 Lottie 模式为 unsupported/disabled；
     不引入 Native overlay、Canvas 或新的异步等待。
   - 实际提交：`8709cbd4d feat(epub): define web-rendered advanced chrome boundary`。
4. **阶段 8：回归契约与收尾**
   - 补齐最小 JVM/runtime 契约测试和必要的可执行探针，覆盖背景、选择、正反向快速翻页、
     章节边界、共享 XHTML、字体和不适用页面；不把 APK、日志、截图或真实书籍加入 Git。
   - 实际提交：`d023faede test(epub): cover embedded reader chrome regressions`。
5. **交付前核对**
   - 已汇总每一步提交、测试命令和未能在 JVM/浏览器环境确认的真机项目；本轮未生成安装包、
     未上传 R2、未发布 Release。

每个阶段固定采用以下提交门槛：

1. 先记录 `git status --short`，确认无关用户修改仍原样保留；
2. 只编辑本阶段列出的文件，必要的跨阶段修正必须单列原因；
3. 运行本阶段最小定向测试，再运行受影响的 EPUB 测试集合；
4. 只按明确路径 `git add`，随后检查 `git diff --cached --name-only` 和 `git diff --cached --check`；
5. 检查暂存差异后创建本地提交，并记录提交哈希；
6. 提交失败或测试失败时不跳到下一阶段。

### 阶段 0：基线（已完成）

源码基线：`0fc81addc`。

时间标记提交：`21b7a6fcd chore(epub): checkpoint before embedded reader chrome`（空提交）。

目的：明确真正的源码回滚点和本轮开始时间。未触碰无关脏文件。

### 阶段 1：模型与策略契约（已完成）

实际提交：`f8b25dc07 feat(epub): define reader chrome geometry contract`

工作：

- 新增 chrome 配置、运行时数据和几何/内容 revision；
- 将 chrome 几何纳入 `EpubCoreLayoutConfig`；
- 将 chrome 的高度、开关、布局模式纳入 Direct chapter cache key；
- 新增适用性策略，不改变默认关闭行为。

验证：

- 默认配置生成的 cache key 与现有行为一致；
- 高度为负、超过视口、仅开启页脚等边界值被安全限制；
- fixed/media/background/scripted 页面全部返回 disabled；
- 纯策略测试通过后再提交。

### 阶段 2：正文真实让位（已完成）

实际提交：

- `dcc725d27 feat(epub): reserve reflow space for reader chrome`
- `43d86f368 fix(epub): apply reader chrome eligibility to layout`

工作：

- 修改 `EpubDirectDocumentBuilder` 的可重排 CSS；
- 正文可用高度明确扣除页眉、页脚和阅读器边距；
- 保留第一页页眉高度，即使文字隐藏也不改变正文位置；
- 加入 runtime chrome 标记的几何排除规则；
- 不修改固定布局、媒体、交互 CSS。

验证：

- 文本首个字形的顶部不小于页眉底部；
- 最后一个字形不超过页脚顶部；
- chrome 文字不增加 pageCount；
- 关闭 chrome 时像素和页数与基线一致。

### 阶段 3：WebView 页面框架（已完成）

实际提交：`802bd2b91 feat(epub): render chrome inside direct document pages`

工作：

- 在 `direct-runtime.js` 创建唯一页面框架节点；
- 实现页面原点计算（LTR、RTL、片段窗口）；
- 实现 `setReaderChrome`、`setReaderChromeData` 和页面首尾状态更新；
- 框架透明、不可选择、不可抢触摸；
- 从 `computePageCount`、锚点、renderable 检查中排除框架。

验证：

- `setPage(0)`、中间页、末页均能定位对应框架；
- RTL 和共享 XHTML fragment window 位置正确；
- 首页只隐藏页眉文字，保留高度；
- 选择文本范围不包含 chrome。

### 阶段 4：Native 配置和动态数据桥接

实际提交：`dbf5bde9f feat(epub): bridge reader chrome data without reflow`

预计文件边界：

- `ReadBookActivity.kt`：只负责从现有阅读配置和当前阅读状态生成配置/数据；
- `EpubReadView.kt`：提供稳定、幂等的公开桥接入口；
- `EpubDirectWebLayer.kt`：缓存最新配置/数据并向匹配 token 的 WebView 分发；
- 新增纯 Kotlin 映射器及测试：集中解释 `ReadTipConfig` 插槽，避免 Activity 和 WebLayer 各写一套规则。

工作：

- 在 `ReadBookActivity` 生成 chrome 配置，复用 `ReadTipConfig` 的经典插槽语义；
- 将书名、章节、页码、进度、时间、电量发送到当前 WebView 和 standby WebView；
- 将几何 revision 和内容 revision 分开传递；
- 统一配置变化入口，避免多个路径分别注入 CSS。
- 配置几何只随阅读设置变化；页码、时间、电量、章节名只改变 `contentRevision`；
- runtime 未安装或 WebView 尚未 ready 时仅缓存最新值，加载完成后一次补发，不等待、不重试轮询、不参与章节激活成功条件；
- 只向 chapter identity 匹配的预加载 WebView 写入章节数据，防止把当前章节标题写进下一章快照。

验证：

- 页码/时间/电量改变不增加 `layoutRevision`；
- 章节改变只更新目标 WebView 的章节数据；
- WebView 未 ready 时数据不会异常抛错或阻塞打开；
- 不新增 Native header/footer 子 View。
- 高级 Lottie 模式在本阶段解析为 disabled，不伪装成经典模式。

### 阶段 5：快照、邻页帧和章节切换同步

实际提交：`76b384565 fix(epub): include reader chrome in page frame identity`

预计文件边界：

- `EpubDirectWebLayer.kt`：页面帧身份、快照前的数据同步、缓存失效；
- `EpubPageFrameTarget.kt` 及相关纯策略：明确 chapter/page/geometry revision；
- 相关 JVM 测试：证明旧页框不能被新几何或新章节复用。

工作：

- 将 `geometryRevision` 加入章节、预加载、邻页帧和 committed snapshot identity；
- 在目标页快照前先设置目标页 chrome，再等待同一帧布局完成；
- 配置几何变化时关闭旧邻页帧、清理旧快照并重新生成；
- 动态文字变化只刷新受影响的已提交快照；
- 不把 chrome 稳定性加入 `resourcesReady` 硬闸门，不新增超时循环。
- 快照捕获前只做一次幂等数据写入；如果 runtime 尚未 ready，放弃该快照并走现有实时页面路径，绝不阻塞逻辑翻页；
- 章节边界预加载数据在预加载时就绑定目标章节，提升为当前页时不再临时重排。

验证：

- 当前页和目标页的章节名/页码不会串页；
- 快速正向、反向翻页没有旧 chrome、闪烁和残影；
- 章节边界目标页的页眉/页脚与目标章节一致；
- 资源迟到时仍沿用现有章节激活机制，不因 chrome 卡死。
- 配置关闭或不适用页面的快照身份与旧引擎保持一致。

### 阶段 6：背景、选择和动画回归（已完成）

实际提交：`973a504b6 fix(epub): keep reader chrome transparent and selection safe`

预计文件边界：

- `direct-runtime.js`：仅补充透明、不可选择、动画中稳定定位所需的最小修正；
- `EpubDirectPageAnimationOverlay.kt`/快照策略：只在审查确认存在重复绘制或不完整帧时修改；
- runtime/策略测试：锁定背景与选择行为。

工作：

- 先用现有测试和调用链确认 `EpubReaderBackgroundPolicy` 与 WebView chrome 的实际绘制
  顺序；只有能复现不透明覆盖或重复绘制时才改动；
- 确认 chrome 的背景、边框和文字均不会替代出版物/主题背景；需要颜色时只允许透明或
  明确由页面主题提供的颜色，不在 Native overlay 中补画背景；
- 移除/禁止任何未来的 Native chrome overlay 路径；
- 保持长按选择期间页面锁定，chrome 不被选中；
- 检查 `EpubDirectPageAnimationOverlay` 只处理完整页面快照，不额外画 chrome；若目标帧
  缺失，沿用既有安全路径，不用纯色伪造半页；
- 反向翻页必须使用与正向相同的完整页面帧契约，取消/中断动画只能恢复一张已提交帧；
- chrome 更新不能触发 MutationObserver 的布局刷新，也不能改变正文锚点。

阶段 6 的实现顺序固定为：

1. 先写/补最小契约断言，锁定当前正确的透明、选择锁定和完整帧行为；
2. 再针对断言失败的最小调用点修改 runtime 或 overlay；
3. 重新运行 runtime 语法检查、相关 JVM 测试和完整 EPUB 定向集合；
4. 只有全部通过才创建本阶段提交。若审查证明现有代码已满足契约，则只提交必要的
   测试/文档，不为了“有改动”而重写动画主流程。

验证：

- 主题背景图和出版方背景图像素不被遮挡；
- 正向/反向、慢速/快速、未完成动画恢复均无残影；
- 长按菜单只出现阅读器菜单，不触发系统 chrome 选择；
- 快照尺寸和分辨率与原页面一致。

### 阶段 7：高级页眉/页脚边界（已完成）

实际提交：`8709cbd4d feat(epub): define web-rendered advanced chrome boundary`

预计文件边界：

- EPUB 正文菜单/配置绑定处：对高级标题显示明确禁用状态；
- 纯策略测试：经典、隐藏、高级三种模式的 EPUB 决策；
- 不新增 Lottie、Canvas 或 Native overlay 实现。

工作：

- 先把 EPUB 的高级 Lottie 模式明确标记为 unsupported/disabled，不显示“已启用”但不渲染；
- 本轮不实现 Web SVG/Canvas 转换；未来若实现，必须以固定尺寸、透明 SVG/Canvas 进入同一页面框架并另立计划；
- 禁止把现有 Native Lottie View 重新叠回 EPUB。

验证：

- 高级模式不会遮挡正文或阻塞翻页；
- 转换失败时只关闭该 chrome，不影响 EPUB 打开和章节切换；
- 经典文本模式不受高级模式代码影响。

阶段 7 不得修改分页、资源就绪、章节激活或动画时序；任何需要这些改动的方案都应退回，
另立故障修复计划。

### 阶段 8：定向测试与收尾（已完成）

实际提交：`d023faede test(epub): cover embedded reader chrome regressions`

预计文件边界：

- `EpubDirectRuntimeAssetTest` 与新建的纯 Kotlin 编排/身份测试；
- `output/playwright/` 下独立可执行探针（只在确有必要时纳入源码提交）；
- 不把 APK、日志、截图或真实书籍加入 Git。

阶段 8 的测试分层：

1. 先运行纯策略/JVM 测试，确认配置、适用性、帧身份和选择状态契约；
2. 再运行 `direct-runtime.js` 语法检查和资源资产测试；
3. 最后运行已有 EPUB 定向测试集合及必要的 Playwright 探针；
4. 对无法在当前环境复现的真机项目明确记录为“待真机验证”，不得用编译成功代替验证。

Runtime/Playwright 测试至少覆盖：

- 多页纯文本、首页隐藏页眉、页脚常驻；
- LTR、RTL、共享 XHTML、片段窗口；
- 自定义字体加载后的稳定布局；
- EPUB 自带背景图、主题背景图；
- 出版方 `<header>`/`<footer>`；
- fixed/media/interactive 页面禁用；
- 快速连续正向/反向翻页和章节边界；
- 文本选择、页面锁定、动态时间/电量更新。

真实书籍回归至少包含：

- 基督山伯爵；
- 最懂输的人才能成为赢家；
- 权财（校对全本）；
- 含注解、共享 XHTML、背景图片和复杂目录的 EPUB。

通过后再单独创建版本/构建提交（如确有版本变更），APK 和 R2 上传产物不提交进源码仓库。

阶段 8 的通过条件不是“测试能编译”，而是以下结果同时成立：

- 关闭 chrome 时旧行为完全保留；
- 开启经典 chrome 时动态字段不改变页数和 `layoutRevision`；
- 当前页、目标页、预加载页和章节边界均使用自己的 chapter/page 数据；
- 正反向快速翻页、取消动画和资源迟到不会产生白页、半页、残影或激活超时；
- 不适用页面自动关闭 chrome，且 EPUB 正文仍可正常打开、翻页、切章和选择文本。

### 本次阶段 8 实际验证记录

使用 JDK 17：

```powershell
$env:JAVA_HOME='C:\Users\Admin\source\repos\legado-private-armv8-release\.build-tools\microsoft-jdk-17.0.16\jdk-17.0.16+8'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

已通过：

```powershell
node --check app/src/main/assets/epub/direct-runtime.js
.\gradlew.bat :app:testAppDebugUnitTest `
  --tests "io.legado.app.ui.book.read.epub.EpubDirectRuntimeAssetTest" `
  --tests "io.legado.app.ui.book.read.epub.EpubPageFrameTargetTest" `
  --tests "io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeDataPolicyTest" `
  --no-parallel --console=plain
```

```powershell
.\gradlew.bat :app:testAppDebugUnitTest `
  --tests "io.legado.app.constant.PageAnimationSpeedTest" `
  --tests "io.legado.app.help.book.EpubContentCachePolicyTest" `
  --tests "io.legado.app.help.config.EpubReadEnginePolicyTest" `
  --tests "io.legado.app.model.localBook.epubcore.*" `
  --tests "io.legado.app.ui.book.read.ReadBookStartupPolicyTest" `
  --tests "io.legado.app.ui.book.read.epub.*" `
  --no-parallel --max-workers=1 --console=plain
```

最小测试与完整 EPUB JVM 定向集合均以 `BUILD SUCCESSFUL` 结束。新增契约锁定：动态页眉/页脚
数据更新不调用布局刷新；章节、href、章节版本、页码、边界语义、布局签名和 chrome 内容版本
均参与页帧身份；几何 key 不随动态文字改变。

以下项目不能由当前 JVM 测试代替，必须在真实 Android WebView 上回归：

- 基督山伯爵、最懂输的人才能成为赢家、权财（校对全本）及含注解/共享 XHTML/背景图的 EPUB；
- 正向与反向快速连续翻页、未完成动画取消、章节边界切换和资源迟到；
- 自定义字体实际加载、主题/出版方背景的像素级遮挡检查；
- 长按选择菜单及选择期间页面锁定。

## 六、验收标准

功能必须同时满足：

1. 正文任何字形、图片和注解都不能进入页眉/页脚保留区。
2. 页眉/页脚随页面和章节移动，快照中一次性出现，不存在第二个覆盖层。
3. 首页隐藏页眉时正文不跳、不重新改变页数。
4. 动态页码、时间和电量更新不触发整章重排。
5. EPUB 自带背景和主题背景不被不透明色覆盖。
6. 反向快速翻页、未完成动画、章节切换不出现白页、半页、残影或旧章节文字。
7. 页眉/页脚异常不能阻塞 EPUB 打开、翻页或切章。
8. 任一阶段都可以通过 `git revert <该阶段提交>` 独立回滚。

## 七、执行纪律

- 未通过本阶段定向测试，不创建该阶段提交。
- 每次提交前检查 `git diff --cached --name-only`，只包含本阶段文件。
- 不使用 `git reset --hard`、`git checkout --` 或清理用户现有文件。
- 不上传 Release；如后续用户确认构建，只上传指定 R2 内测对象。
