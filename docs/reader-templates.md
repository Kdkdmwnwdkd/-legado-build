# 普通正文页面模板

在普通正文的 EPUB 渲染模式中打开「阅读样式 → 页面模板」。可以直接应用内置模板，也可以复制模板，分别编辑首页 HTML、续页 HTML、CSS 和 JavaScript。选择「原有排版」即可退出模板模式。原生正文与出版 EPUB 的排版不受模板选择影响。

内置保留两套创作示例：「星幕月相」演示横排、CSS 标题动效和 JavaScript 页脚动画；「古籍竖排」演示自上而下、从右向左的正文、书框与分页前的图文适配。可以复制后自由改写，也可以通过文件分享自己的模板。「清爽书页」「花园信笺」「小猫游记」「书刊」已退出内置列表，原先复制的用户模板保留。

「星幕月相」保留首页标题入场、中文首字下沉和续页装饰，素材均为本地 SVG/CSS。首页完整显示章名，横屏、矮屏、长章名和大字号优先收紧装饰与留白，再调整标题字号；正文的字号、缩进、段距、对齐和背景继续跟随阅读设置。续页的长书名、章名等提示信息可以省略显示。极端尺寸仍无法容纳正文时触发原有排版回退。复制模板后可以修改这些规则。

管理列表复用应用现有的卡片、操作菜单、文件选择与确认弹窗。每张卡片可直接应用、编辑、预览、导出或删除。删除当前使用的模板会清理阅读样式关联并恢复原有排版；右上角菜单可以恢复当前两套内置模板，已退出内置列表的项目不会复活。升级后仍引用已退出内置列表项目的阅读样式会恢复原有排版。完整说明放在「使用与编写帮助」。

这是高级排版能力。复杂模板会增加首次分页的开销；预览使用正式阅读器的分页与资源加载链路。格式校验通过只表示模板可以保存，实际布局仍需预览。模板错误、分页停滞或脚本长时间无响应会结束本次模板渲染，正文恢复原有排版，保存的模板代码不会删除。

## 首页、续页与正文流

每一章重新从首页开始。HTML 可以有任意页面结构，CSS 不设属性白名单。页面中的一个或多个 `data-reader-flow="body"` 元素承接正文，按数值 `data-reader-order` 排序，相同值按 DOM 顺序。上一块正文区域放不下的内容会接到下一块区域，最后一块放不下的内容进入续页模板。

```html
<article class="page">
  <header><span data-reader-field="bookName"></span></header>
  <h1 data-reader-field="chapterTitle"></h1>
  <main class="columns">
    <section data-reader-flow="body" data-reader-order="1"></section>
    <section data-reader-flow="body" data-reader-order="2"></section>
  </main>
  <footer>
    <span data-reader-field="time"></span>
    <span data-reader-field="page"></span>
    <span data-reader-field="battery"></span>
  </footer>
</article>
```

```css
.page {
  box-sizing: border-box;
  height: 100%; width: 100%;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  gap: .6em;
  padding: 1em;
}
.columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 1em;
  min-height: 0;
}
[data-reader-flow] { min-width: 0; min-height: 0; }
header, footer { display: flex; justify-content: space-between; font-size: .65em; }
.reader-chapter-title { display: none; }
.reader-paragraph { text-indent: 2em; }
[data-reader-page="first"] h1 { font-size: 1.8em; }
```

每页外层标记是 `data-reader-page="first"` 或 `"other"`，`data-reader-page-index` 从 0 开始。正文区域需要可测量的宽高；Grid 布局中通常使用 `minmax(0, 1fr)` 和 `min-height: 0`，避免正文反过来把页面撑高。无需所有区域等宽等高，也无需使用双栏。可以使用 Flex、Grid、绝对定位、装饰元素以及浏览器支持的其他 CSS。

CSS 能力取决于设备的 Android System WebView。自动分页还需要元素能够分片或完整放入区域：无法拆开的超大表格、持续改变大小的脚本、没有高度的正文区域等会产生可见错误并触发回退。此实现不会把浏览器未支持的排版语法当作已经支持。

「古籍竖排」将 `writing-mode: vertical-rl` 设置在正文区域，页面结构、页眉与页脚各自排版。竖排正文需要确定的宽高，段距使用 `margin-block-end`，接排段落不重复首行缩进。模板在分页前按实际区域宽度约束独立图片；图片保持正向显示，段评气泡和书源点击沿用原有 ID。阅读位置仍使用原文偏移，切换模板后会重新分页。

## 精确选择正文

正文具有稳定的语义标记，不必靠全章正则判断段落和标题。

| 选择器 | 对象 |
| --- | --- |
| `.reader-paragraph` / `[data-reader-kind="paragraph"]` | 含文字的正文段落，包括带行内图片的段落 |
| `.reader-chapter-title` / `[data-reader-kind="title"]` | 原始章名 |
| `[data-reader-kind="image"]` | 独立图片或只有图片的段落 |
| `[data-reader-block="block-12"]` | 来源正文的第 12 号块，编号不会随标题显隐而变化 |
| `img[data-legado-image-id]` | 来源图片，包括段评气泡 |

内置装饰模板通过 `beforeLayout` 找到首个正文段落，将原有开头引号与第一个汉字分别包入行内容器，原文和块、图片 ID 保持不变。`beforePage` 按实际字体和行距计算两行首字，不再使用固定像素上限；引号保持普通字号，跨页或跨栏接排不会再次放大。短首段、矮屏、区域过窄、图片开头、复杂行内结构或竖排会使用普通首段样式并恢复阅读设置中的缩进。

模板外层的 `data-reader-ornate-dropcap="auto"` 开启首字适配，`"off"` 关闭。`--reader-dropcap-color` 和 `--reader-dropcap-gap` 可以修改首字颜色与旁边的间距。`data-reader-initial-group`、`data-reader-initial-prefix`、`data-reader-initial-glyph` 是首字与引号的样式标记；`data-reader-dropcap-state`、`data-reader-dropcap-reason` 标明当前页面使用首字效果或回退的原因。

阅读位置以处理后的原文 UTF-16 偏移记录，独立图片不占正文字符。图片与来源点击动作保留各自的 ID。修改 DOM 时保留已有块与图片的 ID，便于搜索、书签和阅读进度继续对应原文；新增装饰内容可以标记 `data-reader-text-ignore`。

## 页眉、页脚与变量

任意元素都能通过 `data-reader-field` 显示一个动态字段，其位置和外观由模板 CSS 决定。

| 字段 | 内容 |
| --- | --- |
| `bookName`、`chapterTitle` | 书名、章名 |
| `time`、`battery`、`batteryPercentage` | 时间、电量 |
| `page`、`pageIndex`、`pageCount` | 本章页码/总页数、从 1 开始的页码、总页数 |
| `progress`、`chapterProgress` | 阅读进度、章节进度 |

基础样式提供 `--reader-text-color`、`--reader-page-background`、`--reader-accent`、`--reader-font-size`、`--reader-line-height`、`--reader-paragraph-indent`、`--reader-paragraph-spacing`，以及 `--reader-padding-*` 与 `--reader-safe-*`。模板 CSS 排在基础样式之后，可以覆盖默认值。正文背景图片启用时，默认页面背景透明。

时间、电量等信息更新后会检查正文区域是否改变。几何尺寸未变时只更新显示；尺寸改变才重新分页。如果页数和正文高度相互影响且无法收敛，会结束本次模板排版，避免空白页循环。

## JavaScript

`javascript` 字段每章执行一次，可以使用真实 `window`、`document`、事件、Promise、fetch 和浏览器 DOM API。HTML 中的脚本属于对应页面；复用续页 HTML 时也会执行，公共初始化代码更适合放到 `javascript` 字段。

可以修改 `source` 中的文字、删除段落、增加内容或调整顺序。分页完整性检查比较的是本轮脚本处理后的正文；替换净化规则仍在此之前处理原始正文。未改动的文字保留原文位置，脚本改写部分则按原段落的邻近位置定位，新增内容不会写回书籍原文。

全局对象 `readerTemplate` 提供正文与分页生命周期：

| 成员 | 用法 |
| --- | --- |
| `source` | 排版前的正文 DOM，可用标准 DOM 选择器访问 |
| `fields`、`viewport` | 动态阅读信息、CSS 像素表示的视口 |
| `pages`、`currentPage`、`pageIndex`、`pageCount` | 已完成的页面及当前页 |
| `motionState` | 当前页的装饰状态：`settled`、`running` 或 `paused` |
| `on(name, handler)` | 注册钩子，返回取消监听函数；排版和导航钩子可以返回有限的 Promise |
| `waitUntil(promise)` | 将异步准备工作加入分页等待 |
| `requestLayout()` | DOM 或样式改变后请求重新分页 |

页面钩子携带 `page` 与 `pageIndex`，可直接操作对应页，避免无意修改其他页面。

| 钩子 | 时机与用途 |
| --- | --- |
| `beforeLayout` | 每次重新分页前修改 `source` 中的正文 |
| `beforePage` | 每个页面承接正文前设置页面结构、正文区域与排版样式 |
| `afterPage` | 本页正文放入后补充装饰，避免再改变正文尺寸 |
| `afterLayout` | 一轮候选分页完成；用事件中的 `pages` 访问这些页面，此时还未替换已显示的页面 |
| `pageChange` | 翻页或目录、书签定位的导航通知，可返回有限的准备任务 |
| `fieldsChange` | 时间、电量等动态字段更新 |
| `motionChange` | 已提交的当前页面对象或实际动效状态改变，包括首次显示、重新分页与导航 |
| `dispose` | 文档离开时清理计时器、动画、事件与网络请求 |

`motionChange` 提供 `page`、`pageIndex`、`pageCount`、`state`、`previousPage`、`previousState` 和 `reducedMotion`。重新分页后，即使页码和状态不变，也会通知新的页面对象。`state` 是综合前后台、分页与减少动态效果后的实际状态，可取 `settled`、`running`、`paused`。

`motionChange` 与 `dispose` 都是同步开始执行的展示、清理通知，不等待返回的 Promise。动画是否播放完成不会阻塞翻页；`motionChange` 的异常仍按模板脚本错误处理。首次页面初始化和重新分页后的 DOM 绑定使用 `motionChange`，导航响应使用 `pageChange`。`dispose` 不是每页退出事件，换页时也需要释放旧页面的任务。

```js
// 精确选择正文段落，给包含引号的段落添加作者自己的样式类。
for (const paragraph of readerTemplate.source.querySelectorAll('.reader-paragraph')) {
  if (paragraph.textContent.startsWith('“')) paragraph.classList.add('dialogue');
}

readerTemplate.on('beforePage', ({ page, pageIndex }) => {
  page.dataset.parity = pageIndex % 2 ? 'even' : 'odd';
});

// 异步资源应显式登记，避免第一次显示后才改变排版。
// readerTemplate.waitUntil(fetch('https://example.com/theme.json')
//   .then(response => response.json()).then(applyTheme));
```

「星幕月相」的 JavaScript 开头就是一个独立的页脚动画示例。以下写法也可用于自己的装饰元素；`Element.animate()` 使用标准 Web Animations API：

```js
let activePage = null;
let animation = null;
function cancelAnimation() {
  if (animation) animation.cancel();
  animation = null;
}
const off = readerTemplate.on('motionChange', ({ page, state, reducedMotion }) => {
  if (page !== activePage) { cancelAnimation(); activePage = page; }
  if (state === 'settled' || reducedMotion) { cancelAnimation(); return; }
  if (state === 'paused') { if (animation) animation.pause(); return; }
  const star = page.querySelector('[data-reader-script-star]');
  if (!star || typeof star.animate !== 'function') return;
  if (animation) animation.play();
  else animation = star.animate(
    [{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }],
    { duration: 18000, iterations: Infinity }
  );
});
readerTemplate.on('dispose', () => {
  off(); cancelAnimation(); activePage = null;
});
```

对应 SVG 装饰可设置 `transform-box: fill-box; transform-origin: center`。用 Canvas 时，可在同一通知中开始或取消 `requestAnimationFrame`，并在 `settled` 状态同步画出完整静态画面。自己的事件监听、计时器与网络请求仍由作者管理，例如在 `dispose` 中移除监听、清除计时器或调用 `AbortController.abort()`。

模板处于独立的沙盒文档中，不能直接访问阅读器的 Java 对象、宿主 DOM 或书源脚本执行器。外部资源遵循浏览器的网络、CORS 和混合内容规则；这不对模板 CSS、HTML 或 JavaScript 源码进行裁剪。沙盒不是独立的 Android 进程，异常检测也不能替代真机验证。

加载会等待分页所需的图片、字体与样式资源，等待设有时限。自行发起的有限异步准备工作应通过 `waitUntil` 登记；不要登记无限动画的 `finished` 或不会结束的循环。脚本、CSS 动画、视频或 canvas 后续产生的每一帧并不等于一次新分页。需要改变正文区域或排版时，请调用 `requestLayout()`，避免在分页钩子内无条件请求下一轮重排。

每页的 `data-reader-motion` 对应装饰状态。模板预分页与原生翻页截图使用 `settled`，此状态应显示完整章名和静态装饰；当前页完成截图并在前台可见后进入 `running`。离屏、界面暂停或段评遮挡时进入 `paused`；减少动态效果开启时保持静态。首页首次播放还标记 `data-reader-entry="playing"`，离开后变为 `done`，翻回不重复入场。

连续装饰可以使用 CSS 关键帧，按上述状态选择器播放或暂停；也可以使用 `motionChange` 控制标准 JS 动画。内置星幕同时展示这两种方式，使用 `transform` 等不改变正文区域尺寸的属性。标题在静态状态保持可见，避免翻页截图截到透明的入场首帧。自由编写的 CSS 和 JavaScript 仍保留浏览器能力；自己的计时器、网络请求和媒体不会自动纳入装饰状态，需要作者处理生命周期。

阅读界面暂停时，依赖 WebView 的原生加载和提交计时会暂停，返回后继续剩余时限。不能用离开界面的时长来推断一次分页是否完成。

滚动模式在模板内连续显示各页，从章节首尾继续向外滑动才切换章节。普通图片的大图弹层与书源图片点击沿用阅读设置；从气泡上开始横向滑动仍可翻页。书源图片动作还会检查当前章节、页码和一次实际的原生点击。

## 文件与兼容性

「导出文件」生成一个模板的 ZIP 文件，「独立备份」生成模板库 ZIP 文件，包含模板源码与内置模板隐藏状态。两者通过「从文件导入」读取，兼容旧 JSON 文件。导入先验证完整内容再提交；不存在的 ID 直接加入，相同内容复用，同 ID 的不同内容另存副本。普通全量备份不再打包整个模板库；单个阅读样式分享仍携带自身关联的模板，旧全量备份中的模板库仍可恢复。

ZIP 中分别使用 `readerTemplate.json` 或 `readerTemplates.json`。模板格式仍为 `schemaVersion: 1`，字段为 `id`、`name`、`description`、`firstPageHtml`、`otherPageHtml`、`css`、`javascript`，HTML/CSS/JavaScript 按原文保存。模板内容变动后，旧分页和截图缓存不会复用。

导入旧阅读样式时，若附带已退出内置列表模板的源码，会完整保留为用户副本；若只携带该内置 ID 而没有源码，则恢复原有排版。未知模板 ID 缺少源码仍会报告错误，避免把丢失的用户作品当作正常导入。

分页适配器固定使用 Paged.js 0.4.3 的 `Layout` 内部接口，按正文区域连续接排，不使用它默认的单正文框页面生成器。来源、MIT 许可和本地导出补丁记录在 `app/src/main/assets/epub/vendor/paged.NOTICE.md`。升级引擎需要重新验证跨区域文字完整性、图片唯一性和阅读器的提交时序。
