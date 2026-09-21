# EPUB Direct Engine Maintenance Guide

Last reviewed: 2026-09-12

## Scope

This document covers EPUB package parsing, XHTML/CSS preparation, resource serving,
layout classification, WebView pagination, gestures, navigation, preloading, and
recovery.

The Direct EPUB engine must not own or extend read-aloud behavior. Role-based TTS,
speaker assignment, sentence playback, and read-aloud persistence remain in the
existing read-aloud subsystem. EPUB may expose ordinary text selection, but it must
not add EPUB-specific speaker buttons, role labels, sentence click handlers, or TTS
state fields.

## Ordinary Text in Direct

`textReadEngine` selects native rendering by default and Direct only for the explicit
`epub` value. `Book.usesDirectReader` combines that setting with the existing EPUB
engine setting; it never changes the book's format or enables Direct for audio,
video, manga, or PDF books. Native and Direct use separate layout files and style
selection/shared-layout preferences. Both profiles are included in backup and
restore. Ordinary Direct and actual Direct EPUB share the EPUB profile.

`TextReaderSessionProvider` reads the original chapter identities and existing
source/local content. It does not create an archive, rewrite a TOC, or reinterpret
source-URL fragments as EPUB ids. A content/source revision and the original
chapter URL guard asynchronous loads. Closing a text session cancels pending
resource/content work immediately, while final session cleanup still respects
leases. Chapter cache deletion, TOC changes, source edits and renderer changes
invalidate the revision.

`TextReaderDocument` generates escaped paragraph/image markup and a common text
coordinate system for the visible document and the existing speech subsystem.
Paragraph rules, native replacement/conversion/segmentation and native paragraph
indent insertion are bypassed. Generated documents are limited to 2 Mi UTF-16 code
units and 20,000 blocks; exceeding a limit gives an explicit error without truncating
the chapter or switching engines. Local-file and network failures remain failures.

Ordinary-book progress and bookmarks store character offsets. Only actual EPUB
continues to store its Direct page coordinate. Generated text markers and a bounded
text cue restore positions after reflow or a renderer change. If transformed text
cannot be matched across renderers, restoration stays in the selected chapter and
starts there. The runtime searches paragraph and character ranges by bisection,
caches the result per page/layout revision, and gates the new bridge callback to
the active document. Actual EPUB documents do not execute those text hooks.

See [the implementation and verification record](plain-text-epub-rendering-plan.md)
for the release checks and device limitations.

## Direct-Only Invariants

- `core` means the visible Direct WebView engine. There is no hidden layout WebView,
  JSON-to-Canvas renderer, paginated Canvas cache, or Canvas selection session behind
  that mode.
- `core` is the default for missing, old, or unknown engine preference values. Plain
  text is entered only when the stored value is exactly `text`, meaning the user
  selected it explicitly.
- A Direct preparation, activation, resource, or renderer failure must never invoke
  the old EPUB core or plain-text reader automatically. Keep an already committed
  Direct page visible; when no Direct page has committed, keep Direct mode active and
  display an actionable error.
- Leaving `core` invalidates foreground, link, and preload requests, clears pending
  callbacks, hides the Direct document, and closes its session. A late asynchronous
  result must re-check both request identity and the current engine preference before
  it can activate a WebView.
- Engine reload releases the current Direct session before clearing book caches. The
  next load must acquire a new facade/session rather than reuse a holder retired by
  cache invalidation.
- Fix Direct failures in the owning parser, resource, layout, lifecycle, or navigation
  layer. Do not restore deleted legacy classes as a recovery mechanism.
- Chromium owns publisher images, backgrounds, SVG, media, and fonts. The Facade must
  not initialize Bitmap image decoders or Android Typeface materializers for Direct.
- Reader startup and page navigation speculatively request the previous and next
  chapter. An index outside the known chapter list is a normal boundary condition;
  filter it before the DAO lookup so it cannot be recorded as a `chapter_null` error.
- EPUB-file Direct startup validates the local file and loads or refreshes the TOC before it
  asks the Activity to prepare the visible chapter. It must not call
  `ReadBook.loadContent()`, `BookHelp.getContent()`, or `EpubFile.getContent()` as a
  prerequisite for the Direct WebView. Plain-text EPUB keeps that standard path.
- A book reset may clear reader state immediately, but it must defer the Direct
  content callback until startup validation is complete. TOC refresh during startup
  also suppresses its normal content reload so exactly one foreground Direct request
  is issued.
- Reader status messages such as missing-file, permission, and TOC errors are Direct
  overlays. Setting a non-null status must never prepare a chapter; clearing the
  status may resume a separately requested load.
- Local EPUB exceptions are failures, not chapter text. Never persist a string such
  as `获取本地书籍内容失败\n...`; remove historical cache entries with that prefix
  before any content consumer can display them.

## Data Flow

1. `EpubPackageParser` reads `container.xml`, OPF metadata, manifest, spine,
   rendition properties, page progression, cover, NAV, and NCX references. Exact
   ZIP paths win; case-insensitive lookup is compatibility fallback. Package,
   manifest, item, NAV, and NCX base paths are resolved before canonical lookup.
2. `EpubTocParser` builds chapter references. Internal fragment identifiers are
   decoded DOM ids; URL serialization encodes them again.
3. `EpubFontDeobfuscatingArchive` transparently decodes IDPF and Adobe-obfuscated
   font prefixes from `encryption.xml`. `EpubCoreFacade` then joins TOC and spine
   entries, prepares continuation documents, indexes canonical resource paths, and
   owns book-scoped caches over that same archive view.
4. `EpubDirectPublisherCss` expands local publisher stylesheets and `@import`
   chains into a classification-only CSS stream. Each stylesheet keeps its own path
   as the base for images and fonts; ordinary render XHTML and `<link>` tags remain
   byte-for-byte independent from that classification stream. BOM and leading
   `@charset` declarations are honored only when CSS must be decoded for analysis
   or continuation inlining; direct resource responses preserve original bytes.
5. `EpubDirectContentClassifier` selects reflowable, publisher-styled, fixed,
   media, or interactive behavior.
6. `EpubDirectDocumentBuilder` parses XHTML once for classification and text. It
   replaces only the reader viewport/style in ordinary XHTML, appends reader CSS at
   the end of the publisher head, and leaves publisher `<base>`, relative URLs, CSP,
   and source markup intact. It does not add a reader-owned base element or CSP.
   `loadDataWithBaseURL(...)` supplies the physical `https://epub.local/...` chapter
   URL. Scoped URL rewriting and DOM serialization are used only for `xml:base` or
   merged continuation documents, then writing/page direction is derived.
7. `EpubDirectSession` coalesces chapter/resource loads and keeps a small chapter
   LRU. `EpubDirectWebLayer` owns active, standby, and preloaded WebViews.
8. The cached `assets/epub/direct-runtime.js` template keeps the publisher DOM
   intact, measures absolute document pages, and exposes a relative page window for
   a logical chapter's start/end fragments. Native code supplies bounded
   placeholders and owns visible position, page animation, recovery, and host
   navigation.

## Startup Diagnostics

The foreground Direct transaction has three named failure stages:

- `open-session`: resolve/copy the local EPUB and open the package facade.
- `prepare-chapter`: resolve the persisted chapter identity and build its Direct
  document.
- `web-activation`: bind the session, install renderer style, and activate or verify
  the WebView document.

Failures from all three stages must use `AppLog.put`, not `putDebug`, and include the
book URL, chapter index, request sequence, full exception class/cause chain, and the
original throwable. This diagnostic remains available in AppLog when record-log mode
is disabled. The user-facing overlay may stay concise, but it must never be used as a
replacement chapter body.

## Navigation-to-Visible Commit Matrix

Every non-swipe chapter change is one transaction. Directory, search, internal-link,
progress restore, and previous/next chapter entry points must converge before Direct
preparation starts; adding a special-case jump after that point is a regression risk.

Directional chapter controls preserve two distinct target-edge contracts. Explicit
previous/next chapter actions, including the two buttons beside the reader progress
bar, always open the target chapter at its start. A page turn crossing a chapter
boundary opens the next chapter at its start when moving forward and the previous
chapter at its end when moving backward. These intents must not share a
direction-only `resetPageOffset` rule.

| Stage | Owner or gate | Typical visible failure | Required invariant |
| --- | --- | --- | --- |
| User intent | TOC/search/progress/reader action | Tap is ignored or expands the row instead | A readable TOC title emits chapter index, href, and fragment; disclosure is a separate action |
| Persisted identity | `EpubDirectNavigationTargetPolicy` and facade normalization | Reader remains in the current physical XHTML | Database index is not treated as a spine identity; book URL, href, and fragment normalize first |
| Pending target | `EpubReadView` | Layout/style reload returns to the old chapter | Exactly one complete target survives zero-size layout, lifecycle reload, and request replacement |
| Candidate ownership | `EpubDirectWebLayer` request/generation gates | Old request wins, blank page, or wrong chapter appears | Request sequence, generation, session, logical chapter, and explicit target must all match |
| Preparation | `EpubDirectSession` and document builder | Main-thread stall before a jump | ZIP reads, parsing, CSS expansion, and preparation stay on IO; duplicate work coalesces |
| Logical range | Runtime fragment page window | Chapter begins at the physical document start, or the next logical chapter repeats | Publisher nodes are never extracted; start/end geometry maps the complete XHTML to a bounded relative page range |
| Target page | Runtime `goToFragment()` and metrics | Chapter loads but stays on its first page | Explicit fragments must belong to the logical range, map to a relative page, and match the page reported before commit |
| Activation | stable/renderable/target gates | Empty or stale candidate replaces visible content | Token, target, stability, renderability, and page verification precede commit |
| Visual commit | Chromium visual-state callback | Header is clipped, page flashes, or old pixels remain | The source remains covered until the committed target has crossed compositor frames |
| Interactive reveal | Source snapshot overlay and live target gate | A fast drag exposes the plain reader background before a publisher background appears | The previous and next visual frames must be complete before gesture progress can expose either side |
| Animation finish | Direct page animation overlay | One-frame white/background flash | Final overlay state is drawn before removal; timeout uses a still handoff only |
| Native progress | committed metrics | Reopen or next action jumps backward | Persist only the page and chapter actually committed by the active generation |

## Reference Alignment

The behavioral reference is the MoTing build derived from Legado, especially its
EPUB direct WebView layer, CSS loader, package metadata reader, and fixed-layout
classification. Reference behavior is a compatibility target, not a reason to copy
known defects or unrelated features.

### 2026-08-17 Text-mode Extraction Alignment

The non-Direct EPUB text path is aligned with legado-E commit
`8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c`. It slices the selected spine resources,
removes non-visible metadata and unsafe executable content, normalizes images to a
single `src`, optionally removes ruby pronunciation nodes while preserving adjacent
base text, and passes `Elements.outerHtml()` to `HtmlFormatter.formatKeepImg()`.

Text mode must not add a synthetic chapter title, wrap output in `<usehtml>`, or
rewrite all publisher elements into a private DOM dialect. Direct native cache
markers and the historical `<usehtml>` text cache are refreshed when text mode is
selected; already formatted plain-text/image content remains reusable. This cache
migration is renderer-specific and does not change the Direct EPUB contract.

### 2026-08-17 EPUB 3 Annotation Contract

Direct mode renders EPUB 3 annotations inside the committed chapter WebView rather
than navigating away from the reader. Explicit `epub:type="noteref"`, `footnote`,
`endnote`, `role="doc-noteref"`, `doc-footnote`, and `doc-endnote` semantics are the
primary contract. The historical marker/target heuristic remains compatibility-only
for books that omit EPUB 3 semantics.

The runtime creates one fixed Shadow DOM overlay so publisher selectors cannot style
the dialog and reader-owned styles cannot mutate publisher markup. A same-document
target is cloned directly. A cross-document target is fetched only from the current
`https://epub.local` origin, parsed with the tolerant HTML parser, and resolved against
the fetched document URL plus any publisher `<base>`. Encoded fragments are decoded
before `getElementById(...)`; relative image and link URLs are converted to the same
physical EPUB resource URLs used by the Direct resource interceptor.

Cloned annotation content removes executable, embedding, form, stylesheet, canvas,
inline-event, dangerous URL scheme, publisher-class/style/id, hidden, editing, and
download state, including when the selected target itself is unsafe. Hidden `aside`
notes therefore become visible only inside the reader overlay. The dialog inherits
the reader foreground/background variables and exposes labelled dialog semantics.
Long content scrolls inside the panel, image clicks reuse the Direct image overlay,
and SVG image URLs use `localName`-aware resolution. The annotation host is an
embedded interaction, so scrolling or dragging it must not start a page turn.

Nested noterefs push an annotation stack. The reader-owned Back button pops that
stack; EPUB backlinks and the close control dismiss the overlay without changing the
chapter page. An ordinary link closes the overlay and then uses the normal `onLink`
bridge. The legacy `EpubFile.getFootnote()`/`TextDialog` path is invoked only when a
recognized local annotation cannot be resolved or rendered.

Opening, scrolling, nesting, and closing an annotation must leave `pageIndex` and the
committed pagination revision unchanged. JavaScript reports visibility for gesture
coordination, but Android back handling asks the active runtime to dismiss the overlay
and waits for that actual result. This avoids a race between a WebView bridge callback
and a back press; a bounded callback timeout continues the normal Activity back path
only when the runtime does not answer.

### 2026-08-15 Release DEX Audit

The comparison below was made against the shipped bytecode, not only JADX Java:

- current release:
  `Legado-3.26.08150423-29778983-arm64-v8a-R8-release.apk`, SHA-256
  `a97cd6e2e69ccd11772cbf8ad4d5381b375a941f858d6206fd19758352e37f27`;
- reference release: `MoTing-3.27.23-32672-arm64-v8a-R8-release.apk`, SHA-256
  `54a1a9c9339887d92873ddbd53422438d4dfee504fb9c66053d2c478ab391629`.

The reference `createWebView` bytecode enables JavaScript, automatic images, and
content access; disables DOM storage, database storage, file access, network-image
blocking, zoom controls, and scrollbars; uses cache mode `LOAD_CACHE_ELSE_NETWORK`,
text zoom 100, transparent background, mixed-content mode `NEVER_ALLOW`, and no
overscroll. It then installs one bridge, one `WebViewClient`, tap/page callbacks, and
a scroll callback. The current layer matches those resource-relevant settings. Its
additional safe-browsing, offscreen-preraster, media, callback-probe, visual-gate,
snapshot, and renderer-recovery behavior is branch-specific and is not evidence of
reference equivalence.

Both releases keep a current WebView and reusable candidate views, load prepared
publisher markup with a physical `https://epub.local/...` base, and route packaged
resources through the WebView client. The reference client asks the document-bound
resource loader for every non-special request and returns an empty response when no
resource is available. The current client instead binds an `EpubDirectSession`,
accepts only its exact virtual host, supports `GET`, `HEAD`, and byte ranges, and
returns explicit HTTP-style errors. This extension must be validated with requested
and painted resource evidence on a device; structural similarity alone does not
prove that images, backgrounds, or fonts render.

The native control layers are materially different. The decompiled reference layer
is about 1,014 Java lines and commits through its current/candidate pool plus page
count callbacks. The current Kotlin layer is over 3,000 lines and adds document
markers, polling, multi-stage activation, renderability probes, snapshots, preload
admission, page-handoff rollback, and renderer-loss recovery. Do not describe this
as a full copy of the reference. Each extra gate needs its own invariant and device
evidence, and it should be removed or simplified only after a failing gate is
identified.

### 2026-08-16 Compositor-backed Page Animation

The shipped reference bytecode captures both page sides with `WebView.draw(Canvas)`.
That is coupled to its pagination implementation: the reference moves the document
body with a CSS transform, so the transformed page commonly appears in a software
View draw. This branch deliberately retains root-scroller pagination because replacing
publisher transforms damages fixed headers, complex layout, and publisher-authored
transforms. On affected Chromium builds, later root-scroller pages exist only in the
hardware compositor; `WebView.draw(Canvas)` then returns a correctly sized background-
only bitmap. Page one works only because it is at the document origin.

The required animation bitmap is the last committed source page. On API 26 and newer
it is captured from the activity window with
`PixelCopy.request(Window, Rect, ...)`, after visual-state and compositor-frame barriers.
The cache key includes generation, WebView token and identity, chapter/page indices, and
viewport dimensions, so a late callback cannot become another page's source. API 21-25
uses the explicitly logged software backend and rejects a uniform result for a chapter
that requires renderable content. Resize, generation changes, hide/pause, memory trim,
renderer loss, destruction, and any translated/covered WebView invalidate or block the
cache. The adjacent-frame pipeline may also supply an optional native-resolution target
frame captured by an isolated hardware renderer. That frame is a temporary visual bridge;
the verified live WebView remains the committed target.

The target side is always the verified live WebView. Logical `Next`/`Previous` action
is independent from the LTR/RTL visual direction: action decides which page covers the
other, while visual direction decides the physical edge. Simulation keeps the target
at `x=0` in both directions. `Next` curls the committed source snapshot over the target;
`Previous` curls the verified previous-page target frame over the committed current-page
snapshot underneath. If that previous-page frame is unavailable, only Simulation motion
is cancelled and logical navigation continues through the verified handoff. Cover moves
the source out for `Next`, but clips the source behind a live target moving one viewport
in for `Previous`. Slide moves both pages one viewport. LinkedCover uses the normal
reader's 20% parallax, target/source masks, and edge shadow in both directions.

Before verification a moving target remains at its normal layout position so Chromium
can raster it under an opaque overlay or the optional adjacent target frame. After
visual-state and compositor-frame barriers, the live target is revealed and translated
with Android view properties. Automatic turns may force a temporary hardware layer;
interactive turns never call synchronous `buildLayer()` while the finger owns progress
and instead retain the WebView's existing hardware-compositor path. A successful finish
keeps an explicitly prepared layer through the final handoff before restoring its original
type. Translation and transient state are reset on every exit. No target uses
`WebView.draw(Canvas)`. Cover and LinkedCover use a narrow direction-aware gradient
rather than a solid side strip.

If the committed source snapshot is absent, capture is scheduled again and the logical
page request continues through the ordinary verified WebView handoff without an overlay.
This is an animation downgrade, not a navigation failure or a substitute animation. If
an animated target does not verify before the bounded deadline, the engine restores the
source DOM page behind the still-mounted source overlay and removes the overlay only
after restore or its restore deadline. Device verification is still required for
compositor output; JVM tests validate ownership, ordering, translation, and rollback
rather than pixels.

### 2026-08-16 Interactive Drag Contract

- A horizontal drag mounts one native snapshot overlay before changing the runtime
  page. Every subsequent move maps finger displacement directly to overlay progress;
  releasing the gesture settles that same overlay instead of starting a second
  animation from zero.
- Same-chapter turns apply the target page beneath the mounted overlay. Adjacent-
  chapter turns keep the overlay mounted across asynchronous candidate preparation
  and activation. A neighbouring WebView may become the live preview only after its
  target viewport, packaged images/fonts, visual state, and compositor frames verify.
  The preview uses the same semantic chapter-start/chapter-end activation target as
  final commit; it never freezes `preparedPageCount - 1` as an absolute final page.
  Until then the opaque overlay or optional target frame remains visible. Do not reveal
  the old live WebView as the target side of the turn.
- Styles with a moving live target change the touch receiver's Android translation only
  after target verification. Gesture distance and velocity therefore use raw screen
  coordinates, not View-local coordinates altered by that translation.
- Short drags and reverse flings settle back to the committed source. A committed
  chapter-boundary drag settles visually to the endpoint while the opaque overlay stays
  mounted, but logical commit and overlay removal still wait for the semantic boundary
  target, current layout revision, and target viewport to verify. Book boundaries,
  preparation failure, and timeout release the pending turn and restore or remove its
  overlay.
- Taking an adjacent frame must not synchronously refill its renderer slot. Interactive
  turns suspend near/far virtual-WebView scheduling before taking a frame, keep target
  bookkeeping current without starting new renders, and resume refills on a display frame
  only after commit or rollback has removed the animation overlay.
- Once touch release chooses commit or rollback, later touch events must not change
  the existing turn's progress. A new deliberate swipe joins the turn queue while
  settlement finishes; a short rejected swipe must not snap the busy target DOM back.
  A boundary turn remains eligible for
  cleanup while its target token is current, including the race where timeout rebound
  and WebView activation occur in adjacent UI-loop tasks.
- Fixed-layout and runtime single-page chapters use this boundary path when their only
  page is exhausted. Cover, linked cover, slide, and simulation share the same drag
  ownership rules; style-specific drawing remains inside
  `EpubDirectPageAnimationOverlay`.
- JVM policy and source-contract tests cover ordering, state ownership, rollback, and
  activation handoff. No physical-device feel test was completed for this change.
  Do not claim final visual correctness until Cover, LinkedCover, Slide, Simulation,
  RTL, fixed layout, image-heavy pages, embedded controls, short-drag rebound, reverse
  fling, and both chapter directions pass on a real device.

### 2026-09-12 Page-turn Gesture and Simulation Continuity

- Direction locking subtracts only the configured touch slop, preserving the movement
  already received in the first qualifying frame. Release incorporates the final UP
  sample and bounded raw-coordinate velocity. Slow short drags roll back; intentional
  drags and directional flings commit. Reversals accumulate a touch slop before changing
  the release decision, so subpixel jitter does not cancel a page turn.
- Simulation uses the ordinary text reader's top/bottom corner selection, flat middle
  gestures, bottom-corner previous page, Bezier fold, shadows, and reflected page back.
  The tip follows finger displacement one for one. A complete fold travels two viewport
  widths, including the offscreen half that the previous overlay omitted. RTL mirrors
  geometry while retaining the logical source/target bitmaps and readable front text.
- Interactive vertical position is retained when settlement starts, then converges to
  the selected corner. Source and target endpoints draw complete page snapshots, and a
  verified live target remains visible when an outgoing overlay completes.
- LinkedCover eases the remaining release segment. Reapplying easing to an absolute
  drag position would otherwise jump the page on the first release frame. A forward
  fling can shorten settlement; the selected preset still bounds its duration.
- A second pointer cancels the native page gesture. Pointer replacement cannot become
  a large swipe, and an unclaimed embedded pinch/zoom continues in the WebView.
- The draw loop reuses intersection points and edge gradients. Geometry uses viewport
  dimensions independently of snapshot resolution, matching native shadow dimensions.
- JVM tests cover gesture traces, kinematics, RTL and settle continuity. Android pixel
  tests live in `EpubDirectPageAnimationOverlayTest`; they require an Android device.
  This host has no connected device and its recorded emulator environment cannot boot,
  so physical touch feel, hardware composition and frame-time performance remain
  unverified. Build/check results are recorded under `output/page-turn-feel-*`.

### Shared Page-animation Speed Contract (updated 2026-09-12)

- Plain-text and Direct EPUB readers share one persisted base-duration preset:
  `Extreme`/`极速` is 180 ms, `Standard`/`标准` is 300 ms,
  `Relaxed`/`舒缓` is 420 ms, and `Elegant`/`优雅` is 560 ms. Missing or
  unknown stored values normalize to Standard.
- The base duration applies to click, key, automatic, and released-gesture settling.
  During a live drag, progress remains the clamped ratio of raw finger displacement
  to the style's full travel distance; changing the preset must not add interpolation, throttling,
  preload work, or any other latency to touch-owned frames.
- Cover and Slide use the selected base duration. Simulation follows the native
  duration-per-page-width contract: a full two-width trajectory takes `2x` the base.
  LinkedCover keeps the ordinary reader's existing `1.35x` duration. Partial settlement
  uses the remaining distance and may shorten for a matching fling, with a minimum
  of 32 ms for a nonzero segment.
- Speed is read when a settle or automatic turn starts, rather than cached when the
  reader view is constructed. Changing the option therefore affects the next turn
  without rebuilding the document or invalidating EPUB preloads.
- LinkedCover settlement uses the ordinary reader's
  `PathInterpolator(0.4, 0, 0.2, 1)` and the same blended cubic draw easing. Other
  styles remain linear unless their ordinary-reader delegate defines another curve.
- A body tap is dispatched immediately after Chromium handles `ACTION_UP`; it has no
  fixed deferral. JavaScript touch arbitration and the synchronous WebView hit result
  suppress links, editable fields, media, form controls, galleries, and nested
  scrollers so removing the delay cannot turn an embedded interaction into a reader
  tap.
- Target viewport metrics are sampled only after the visual-state/compositor barrier.
  Metrics captured by the page-setting JavaScript call are not final verification,
  and any sample with a pending layout revision is rejected.
- A verified target starts motion without a second compositor-frame barrier. The
  animation overlay is removed immediately after its final state has drawn; a bounded
  48 ms callback remains only for a missing draw notification, not as normal latency.
- Missing or pending animation snapshots never make logical navigation wait. They
  trigger a new capture and the request continues through the verified still handoff.

### 2026-08-16 Fast-drag Target-frame Gap Baseline

The device-observed baseline is
`Legado-3.26.08161550-29781110-arm64-v8a-R8-release.apk`, SHA-256
`da55b2473eb8cfbec9120ed421dcc082ddd8c6c33c3dc8ea7f6d4ad7ee7c392c`.
Its signing certificate SHA-256 is
`d6441f18d3413a81c0e82e51f550c558c5d79213e921b02dec30bd3473d4f2b6`.

The confirmed symptom is timing-dependent: a slow drag reveals the publisher
background correctly, while a fast drag briefly reveals the plain reader background
and then the publisher background appears. This is not evidence that the packaged
image is missing. Interactive progress begins immediately, but the same-document
target is changed only after the source snapshot is mounted. Until target page,
viewport, visual-state, and compositor gates finish, the animation overlay remains
opaque and paints only `EpubCoreLayoutConfig.backgroundColor` behind the translated
source snapshot. A slow gesture outlasts that gate; a fast gesture exposes a larger
region before `revealLivePageAnimationTarget()` clears the opaque fill.

Resource preload and final visual-frame preload are distinct. A decoded CSS background
can already be present in Chromium while the opaque animation fill still hides it, and
a loaded document does not guarantee that both adjacent viewport textures have been
captured before touch. The replacement contract is therefore stricter than the current
live-target gate: previous and next `PageFrame` values must contain independently
captured, native-resolution hardware output before a drag may expose them. Animation
must draw an opaque target frame first and the source frame above it; the live WebView
may commit under that target frame but must never be the only content behind a moving
source.

Repeated page requests while an overlay, animator, chapter transaction, activation, or
handoff is active enter a bounded FIFO of direction runs. The drain peeks without
consuming while busy and removes one request only after a page move or boundary callback
is accepted. Busy drains reschedule themselves without reinserting the same request.
The completed target frame may become the next source, while a missing animation frame
can only remove motion and never remove the queued logical action.

### 2026-08-16 Horizontal Last-page Extent

Chromium multi-column layout reports `scrollWidth` without the trailing reader padding.
For a 400 px viewport with 24 px padding, a four-page document therefore reports 1,576
px and clamps the final offset to 1,176 px instead of 1,200 px. This shifts only the
physical last page toward the screen edge.

After measuring publisher content, the runtime mounts a one-pixel, non-flow extent
marker under `documentElement`, outside `body`, at the complete physical page count
times the viewport width. The old marker is detached before every measurement, so it
cannot preserve a stale page count. LTR and RTL use symmetric left/right positioning;
logical fragment windows still use the complete XHTML page count. Reflow preserves the
old normalized scroll offset while the marker is rebuilt. The marker is absent in
vertical and fixed layouts and cannot enter content, renderability, or anchor walkers.

The Chromium probe verifies both directions: 2,776 px becomes exactly 2,800 px for a
seven-page 400 px fixture, and both normalized last-page offsets reach 2,400 px without
creating an eighth page.

The same release DEX audit checked initialization before changing runtime behavior:

- `EpubReadView`, `EpubDirectParsedSource`, and `EpubDirectChapter` have empty class
  initializers;
- `EpubDirectSession` initializes only its companion;
- `EpubDirectWebLayer` initializes a lazy runtime-asset delegate and one placeholder
  regex;
- `EpubDirectDocumentBuilder` initializes two attribute lists and nine regexes used
  by URL, viewport, CSS, direction, and raw-head preparation.

No missing release class or invalid call was visible in those initializers. A top-level
`ExceptionInInitializerError` therefore does not identify a repair. Preserve and show
its nested exception class and message before changing regexes, resource routing, or
the Core selection path.

The subsequent affected-device failure was `NoClassDefFoundError` for an EPUB Direct
class after the earlier initializer failure. APK Analyzer confirmed that every critical
Direct class is defined in the release APK and that the APK has no referenced-only
`io.legado` class. This is the ART behavior expected after a class initializer fails:
the first failure reports `ExceptionInInitializerError`, while later uses in the same
process can report only `NoClassDefFoundError` for the now-erroneous class.

EPUB regex construction is therefore isolated by `EpubRegex`. A pattern rejected by a
device runtime becomes a non-matching optional rule instead of aborting the owning
class initializer. Package, TOC, font, classification, publisher CSS, document, range,
and document-marker rules all use this boundary. Direct runtime placeholders do not
use regex matching; every known placeholder is replaced explicitly and unresolved
tokens still fail before WebView loading. Reader-font cache revisions use arithmetic
unsigned conversion rather than the platform-dependent `Integer.toUnsignedString`
overload. These changes contain the identified initialization failure category without
switching engines or bypassing Direct resource handling.

The reference Direct path loads prepared chapter markup with
`WebView.loadDataWithBaseURL(...)` and serves `https://epub.local/...` CSS, image,
and font requests from `WebViewClient.shouldInterceptRequest(...)`. Keep that split:
the synthetic HTTPS URL is a resource origin and base URL, not a network-loaded main
document. A fake-HTTPS `loadUrl(...)` main document introduces certificate handling
that the reference architecture does not require.

| Area | State | Notes |
| --- | --- | --- |
| Recursive publisher CSS | Aligned and extended | Six import levels, 2 MiB per CSS, 8 MiB per preparation, screen-active media queries retained |
| ZIP path identity | Extended | Exact path first, case-insensitive fallback, canonical paths propagated through package, TOC, chapter, links, and resource serving |
| Duokan declarations | Aligned | `duokan-text-indent` normalized; `duokan-bleed` removed |
| CSS/resource base paths | Aligned and extended | Ordinary XHTML uses the publisher `<base>` plus the WebView chapter base unchanged; element `xml:base` and continuation scopes are absolutized only when required |
| CSS byte encoding | Extended | UTF BOM and leading `@charset` honored for classification and continuation inlining; direct responses remain byte-preserving |
| Embedded font obfuscation | Extended | IDPF SHA-1/1040-byte and Adobe UUID/1024-byte schemes decoded through one archive layer |
| Resource MIME/charset | Extended | Known file extensions override generic or incorrect OPF MIME; CSS response encoding remains unset for Chromium detection |
| OPF rendition metadata | Aligned | Layout, orientation, spread, viewport, spine overrides, token normalization |
| Content classification | Aligned and guarded | Fixed, media, interactive, gallery, artwork, title/decorative pages; short normal chapters stay reflowable |
| Horizontal/vertical paging | Extended | LTR/RTL document scrolling, vertical writing direction, nested-scroller arbitration, boundary callbacks, and short-drag page snapback |
| Fragment navigation | Extended | Encoded NAV/NCX fragments normalize to DOM ids; arbitrary cross-file anchors use a bounded document-order owner index |
| Media and byte ranges | Extended | Memory/disk resource caches and HTTP range responses |
| WebView lifecycle | Structurally aligned, behavior unverified | Both use current/candidate WebViews; this branch adds marker verification, visual commit, preload LRU, renderer-loss recovery, and snapshots |
| Resource readiness | Partially aligned, behavior unverified | Both wait for bounded image/font settlement; this branch adds renderability and viewport gates that still need affected-device evidence |
| Orientation and spreads | Parsed only | Metadata is retained but device orientation locking and synthetic two-page spreads are not implemented |
| Read-aloud integration | Out of scope | Must stay in the existing read-aloud subsystem |

Do not describe the engine as fully aligned until the pending device matrix below
has passed. JVM tests cannot validate Android WebView composition or touch dispatch.

## Compatibility Rules

- OPF property tokens are stored lowercase. Invalid rendition and page-direction
  values must fall back to defaults instead of changing layout.
- Resolve a normalized exact ZIP entry before using the case-insensitive index.
  Publishers sometimes ship two entries that differ only by case; compatibility
  lookup must not override an exact request.
- A viewport alone does not prove fixed layout. OPF/spine metadata and conservative
  canvas detection are required.
- Ordinary short chapters must not become implicit single-page documents. Reserve
  that behavior for artwork, decorative title pages, compact media, and explicit
  fixed/interactive content.
- A failed or oversized stylesheet must not fail the chapter. Keep the original
  `<link>` or `@import` so the WebView resource interceptor can still attempt it.
- Ordinary spine XHTML is injected as a string and must retain publisher markup,
   attribute spelling, and stylesheet links. DOM serialization and scoped URL
   rewriting are reserved for merged continuation documents whose resources have
   a different physical base path and documents that declare `xml:base`.
- Preserve an ordinary publisher `<base href>` exactly and let Chromium resolve it
  against the physical chapter URL passed to `loadDataWithBaseURL(...)`. Do not add
  a reader-owned `<base>` or absolute URL rewrite to ordinary documents.
- Insert reader CSS after publisher head styles so the intended reader overrides keep
  their cascade priority. Do not inject a reader-owned CSP. A generated policy can
  interpret a vendor WebView's `loadDataWithBaseURL(...)` document as an opaque
  origin and block packaged styles, images, backgrounds, or fonts together.
- Classification and continuation CSS decoding honors UTF byte-order marks and a
  leading supported `@charset`, then removes that declaration before combining
  rules. Unsupported declarations fail open to UTF-8 and must not fail a chapter.
- The OPF `unique-identifier` target, not the first arbitrary `dc:identifier`, is
  the font-obfuscation key source. Remove only XML whitespace before IDPF SHA-1;
  Adobe decoding requires a valid UUID key. Both byte-array and streaming reads,
  including Range skips, must expose decoded bytes.
- Direct main documents are loaded as `text/html`, matching the reference engine.
  EPUB markup is often XHTML-shaped but not XML-valid; using an XML MIME type can
  reject named entities or malformed publisher markup and produce a blank or
  partially styled document.
- Local chapter and resource URLs use the fixed `https://epub.local/` origin used by
  the reference engine. Do not generate per-book subdomains, enable file access, or
  enable broad network access to work around path bugs.
- `onPageFinished(...)` callbacks may omit the synthetic Direct load token or
  normalize percent-encoded paths. Match the scheme, host, normalized path, and a
  token only when the callback retains it. Do not loosen this to arbitrary local
  URLs. The callback is an optimization, not proof that the requested DOM won the
  navigation race: each `loadDataWithBaseURL(...)` document carries a per-load
  marker and the reader verifies that marker before pagination or runtime install.
  The Direct reader polls this marker as a bounded recovery path when a vendor WebView
  omits the callback or its visual-state callback. Log expected, callback, and view
  URLs before using the polling path.
- Publisher resource responses use wildcard CORS because opaque documents send
  `Origin: null`, while normal WebViews use the fixed EPUB origin. This does not
  broaden routing: only the exact bound `epub.local` virtual host is intercepted, and
  all other network requests remain blocked. Failed intercepted responses are
  `no-store` so a transient session/read failure cannot poison Chromium's cache.
- `shouldInterceptRequest(...)` executes on WebView IO threads. The session and
  reader-font configuration read there must be published through volatile state
  before loading a document or promoting a preload. A client captures that session,
  configuration, and its load token; a late request from a replaced client must not
  mutate the active chapter's state.
- Direct resource responses match the reference cache contract with
  `public, max-age=31536000, immutable`; failed responses remain non-cacheable.
- The built-in reader font is a low-priority `html` inheritance default. When the user
  selects a custom reader font, the Activity explicitly enables publisher-font override
  and the document builder applies it to reflowable and publisher-styled prose with
  `!important` on `body, body *`.
- A preloaded WebView has three distinct states: scheduled, loading, and visually
  ready. When a custom reader font is configured, its targeted `document.fonts.load`
  promise must settle successfully or with an error before the first two-frame stable
  publication, so the first exposed page count uses the selected font. The same font
  settlement cannot publish that initial stable state twice. Publisher fonts, images,
  backgrounds, and other resources settle in a bounded second phase and trigger another
  layout report. Aggregate
  `resourcesReady` and `resourcesFailed` values are diagnostic/reflow signals, not
  reasons to discard an otherwise renderable Direct chapter or preload. Visual-only
  pages still require decoded pixels in both document and target viewport checks.
- An `<img>` or SVG `<image>` rectangle is not proof of rendered pixels. Direct
  runtime readiness and viewport probes accept an HTML image only when it is complete
  and has positive natural dimensions; pure SVG image containers use a decoded URL
  probe. SVGs with actual vector shapes remain immediately renderable. A CSS URL
  background qualifies only after its probe image decodes; gradients also remain
  immediately renderable. This prevents broken or pending image boxes from committing
  an otherwise blank page.
- Embedded controls, forms, media, galleries, frames, editable nodes, and actual
  nested scrollers keep their gestures. A generic scroll container claims a gesture
  only after it can scroll in that direction.
- Image enlargement is an unmoved 500 ms long press (or platform `contextmenu`
  equivalent), not an ordinary click. Moving at least 12 px, adding another pointer,
  or cancelling the touch aborts enlargement; one accepted hold emits exactly one
  image bridge event. Ordinary image clicks remain available to reader taps and links.
- Visible content stays mounted until a candidate WebView reports stable,
  renderable pixels. Do not replace this with an `onPageFinished`-only commit.
- Reader-only style changes use `EpubDirectWebLayer.reloadStyle(...)`. They preserve
  the committed Direct document under a transition snapshot and must not restart the
  Activity's full chapter transaction merely to update color, font, or spacing.
- A Direct failure must not turn image `alt` text or filenames such as `logo` and
  `emoji` into visible replacement content. Diagnose the failed packaged-resource
  request and keep the rendering transaction inside Direct.
- Logical chapters sharing XHTML keep the complete publisher DOM. Renderability,
  page counts, and fragment ownership are scoped by geometry to the chapter's
  relative page window; fixed or sticky running furniture must not qualify an
  otherwise empty logical chapter or extend its page boundary.
- Horizontal reflow moves the document scrolling element to an absolute physical
  page and reports a relative logical page. Do not restore body-level paging
  transforms: they overwrite publisher transforms, clip fixed headers, and force a
  full-body compositor update on every turn.
- Any EPUB-specific read-aloud UI, events, bridge methods, or persistence is an
  architectural boundary violation.

## Performance Budgets

| Resource | Budget |
| --- | --- |
| Prepared XHTML source cache | 4 entries, 4 Mi characters per entry, 8 Mi characters total |
| Prepared chapter cache | 4 layout-keyed chapters and 8 Mi characters per Direct session |
| In-memory binary resources | 8 MiB per entry, 18 MiB total |
| Publisher CSS | 2 MiB per file, 8 MiB total, six import levels |
| Package and TOC documents | container 1 MiB; OPF, NAV, and NCX 8 MiB each |
| Font encryption metadata | `META-INF/encryption.xml` 1 MiB; decoded prefix only, resource length unchanged |
| Fragment owner indexes | 8 XHTML documents; source reads capped at 8 MiB each |
| Preloaded WebViews | 1 on low-RAM or under 384 MiB memory class; otherwise 2 |
| Animation snapshot | 600,000 pixels on low-RAM or under 384 MiB memory class; 1,500,000 pixels otherwise |
| Pending chapter-turn snapshot | At most one source bitmap, retained for at most 12 seconds and transferred to the animation overlay on commit |
| Horizontal wrapper normalization | 24 candidates total; direct body children plus pure single-child wrapper chains; maximum depth 8 |

Path indexes for manifest resources, spine items, and chapter links are built once
per book. Do not reintroduce linear manifest scans into `shouldInterceptRequest`.
Direct resource bytes below the entry limit reuse the bounded memory cache; larger
resources stream under an archive lease until the WebView response closes. Publisher
fonts remain packaged Web resources and are decoded by the shared deobfuscating archive
before Chromium receives them. No Android Typeface or Bitmap cache participates.
Classification and plain-text extraction share one lazily parsed Jsoup DOM per
prepared chapter, but ordinary document output remains the original XHTML string.
External CSS must participate in classification through the separate bounded CSS
stream. Do not inline it into ordinary render XHTML or serialize that DOM merely to
inject reader CSS: relative resources already resolve against the WebView base URL. Merged
continuation documents are the exception because each section owns a different
physical base path and therefore still needs scoped URL rewriting. The runtime
JavaScript lives in an asset and is cached once per process; do not move the template
back into a Kotlin interpolated string because it creates pathological release
compiler optimization work.
Font deobfuscation is an archive decorator, not a WebView-only patch. Direct resource
caching, disk Range extraction, and streaming responses must all observe the same
decoded bytes without materializing large fonts solely for decryption.
Website-specific scrambled-font substitution is not part of EPUB deobfuscation.
Downloaders that clean a website and emit an ordinary EPUB provide no generic glyph
mapping contract; do not add site-specific character tables to this engine. EPUB
fonts are handled through standard CSS resource loading plus IDPF/Adobe prefix
deobfuscation declared by `META-INF/encryption.xml`.
Prepared-source cache identity is physical XHTML plus its continuation-document
set, not logical chapter index or start/end fragment. Multiple TOC anchors that
address one XHTML must share the same prepared source string; generated image/audio/
video wrappers additionally include title and normalized MIME because those values
change the generated document.
Logical fragment ranges are pagination metadata, not DOM ownership. The runtime
measures start/end anchors against the complete document, exposes
`windowStartPage`, `windowEndPage`, and `documentPageCount` for diagnostics, and
maps native `pageIndex/pageCount` to that relative window. An out-of-window
fragment returns failure so native navigation can load the owning logical chapter.
The end boundary includes a shared physical page only when bounded backward
geometry finds preceding renderable flow content on that page. Viewport-anchored
fixed/sticky decorations are excluded from this decision and from logical
renderability, while remaining mounted and visible.
The prepared-chapter LRU is bounded by both entry count and total HTML/plain-text
characters. A chapter larger than the total budget is returned to its requesting
WebView but is not pinned in the Session cache; active and standby WebViews remain
the owners needed for immediate display and back navigation.
Horizontal reflow normalization must never query every generic container in a
chapter. It inspects direct body children and descends only through a pure wrapper
with one content element and no direct text. Forms, editable content, dialogs,
listboxes, and galleries stop the chain. Only a vertically scrolling candidate at
least 72 percent of viewport width and 90 percent of viewport height may have its
fixed height released. On the 2026-08-11 Edge/Playwright 400 by 700 probe, the old
runtime changed a nested `80vh` notes scroller and performed 12,009 synchronous
style reads for 2,400 rows. The bounded runtime preserved that scroller, performed
one wrapper-normalization style read, and performed 2,404 to 4,810 total reads
depending on whether the first bounded background-image scan completed in the same
task. Measured script installation ranged from about 23.8 ms to 66.5 ms, versus
about 80.6 ms before the fix. These figures are a regression baseline, not a device
performance guarantee.

## Recovery and Lifecycle

- Every asynchronous load and JavaScript report carries a generation token.
- Hiding Direct mode invalidates the committed document state and navigates its
  current WebView to `about:blank`. Returning from an explicitly selected text mode
  must not expose an old Direct chapter while the next candidate is still verified.
- A standby or preloaded WebView is not visible until metrics and visual-state
  checks pass.
- Renderer loss invalidates stale callbacks, releases the failed view, and attempts
  recovery while preserving the last visible snapshot where possible.
- Closing a session clears text/binary caches and waits for active resource leases
  before closing the EPUB archive.
- Facade close drains the Direct disk range-cache extractor and then asks the leased
  archive to close. The archive rejects new
  reads and waits for active WebView interception reads and returned resource streams
  to release their leases before closing `ZipFile`; no worker may retain access to an
  archive that has already closed.
- Low-RAM devices receive smaller preload and snapshot budgets. `ReadBookActivity`
  forwards low/critical memory callbacks to `trimDirectMemory()`, which discards
  Direct preloads and nonessential animation snapshots while preserving the visible
  WebView and any current-generation foreground candidate.
- Runtime installation completes before a promoted preload can enter visual
  activation. A failed reused-runtime handshake receives one full reinstall; a
  second failure discards the candidate instead of presenting an unpaged document.
  Explicit TOC navigation preempts stale foreground work; no paginated Canvas cache
  exists in Direct mode.
- A preload may enter the ready cache only after load completion, runtime installation,
  stable metrics, target renderability/viewport checks when required, and
  `resourcesReady`. Layout stability alone is not resource readiness and must not
  promote a candidate that is still resolving publisher images, CSS, backgrounds, or
  fonts.
- Candidate activation probes continue at bounded intervals until the transaction's
  3-second hard deadline instead of stopping after a fixed attempt count. A runtime
  stable callback for that same pending view wakes the existing probe immediately;
  it must not create a parallel probe. Timeout only fails and restores the committed
  reader; it never commits an unverified candidate.
- Chapter-boundary activation uses the semantic target `start` or `end`, not an
  absolute page captured from an earlier pagination. The runtime publishes a monotonic
  `layoutRevision`, `layoutPending`, and the revision in which that semantic target was
  applied. Every late reflow reapplies the boundary target before it becomes eligible
  for commit. Native activation requires no pending layout, matching target/layout
  revisions, the resolved boundary page, and a verified viewport. Aggregate
  `resourcesReady` remains a diagnostic/reflow signal rather than an activation gate.
- Candidate setup, loading, or activation failure synchronously clears queued turns,
  pending chapter state, handoff/animation overlays, recovery snapshots, and every
  cache reference to the failed WebView before destroying it. When a different Direct
  WebView already owns the committed chapter, restore that view at normal translation,
  alpha, visibility, and z-order and refresh its adjacent frames. Never recover by
  switching to the old EPUB core.
- Horizontal page animations keep the source snapshot fully mounted until Chromium
  commits the target visual state. Every style then reveals the verified live WebView
  through transparent overlay regions. No normal or chapter turn captures a target
  bitmap.
- A horizontal page turn at a chapter edge starts one bounded transaction before host
  navigation. It records the committed source WebView, chapter, logical/visual
  direction, configured animation style, and optional `RGB_565` source snapshot. Only
  an explicitly marked boundary request for the exactly adjacent chapter may consume
  it, and the incoming chapter must verify its first page when moving forward or final
  page when moving backward. TOC, progress, fragment, search, and explicit chapter
  jumps cancel the transaction; the target logical chapter's own start anchor is
  boundary metadata rather than an explicit fragment jump. A chapter that changes
  horizontal page progression direction also commits without motion, because the
  source chapter's visual direction cannot describe that target safely.
- After the adjacent candidate passes page, viewport, and visual-state verification,
  chapter turns use the same `EpubDirectPageAnimationOverlay`, duration, and
  `DecelerateInterpolator(1.35)` path as turns inside a chapter. The incoming candidate
  is the live target for every style. If the committed source bitmap is unavailable,
  navigation is still dispatched exactly once, snapshot refresh is scheduled, and the
  verified incoming WebView is exposed without motion; there is no second renderer or
  substitute animation.
- Direct page requests return an explicit result: moved within chapter, boundary
  required, queued, rejected, or unavailable. The host requests another chapter only
  for `BoundaryRequired`; queued and rejected inputs can never be mistaken for a chapter
  edge. A queued boundary callback and an accepted interactive boundary bypass input
  debounce, while debounce may reject only a new unaccepted gesture and must never
  cancel an existing pending chapter transaction.
- A same-chapter animated turn must verify its expected page index, renderable content,
  and target-viewport geometry before revealing it. A missing, mismatched, or non-
  renderable target restores the source page while the committed overlay remains
  mounted.
- A valid committed source bitmap selects the configured overlay animation. If source
  capture is unavailable, horizontal paging requests another committed PixelCopy capture
  and continues through the verified non-overlay handoff. It must not choose another
  animation style, enqueue only because animation material is missing, or report that a
  logical page request was handled while leaving it unexecuted.
- A normal same-chapter turn mutates the page first, then crosses one visual-state
  barrier before target metrics verification. `applyPage(...)` must not add
  another compositor barrier: every page-turn caller owns target verification, and
  redundant barriers delay animation without increasing the verified state.
- A visual-state callback is followed by compositor frames before animation starts.
  The overlay draws its final transparent/target state at least once before removal;
  removing it directly from `onAnimationEnd` can reveal an incompletely composed
  live WebView and produce a one-frame flash. A visual-state timeout performs a
  still, no-animation handoff and must never start motion against an unverified page.
- Snapshot capture samples a bounded 64 by 64 grid for pixels that differ from the
  reader background. A content-bearing chapter never starts from a uniform background
  source bitmap returned by an incompletely composed vendor WebView. Target pages are
  never sampled into animation bitmaps; renderer recovery also refuses to pin an empty
  snapshot over the replacement.
- Before the first Direct document is committed, the reader paints a full-page
  loading background instead of exposing an empty WebView-sized content area.
- Runtime renderability checks share computed-style, gallery-ancestor, and
  viewport-anchor caches within one layout revision. They accept only geometry
  intersecting the logical page window. Image, font, DOM, resize, and media changes
  advance the revision before another result may be reused.
- Runtime position reports use a page-count/page-index-only path. Scroll, page-turn,
  and mutation reports must not invoke the full renderability walk that activation
  uses; after a dirty layout, page counting is already one complete geometry pass
  and a second DOM traversal causes avoidable long-chapter stalls.
- Adjacent Direct chapters are prepared serially: the next chapter first, then the
  previous chapter within the selected scheduling profile and the WebLayer's device
  capacity. Light keeps one forward preload, Normal staggers both directions, and
  Performance removes the stagger without exceeding the memory-derived WebView cap.
  Every stage revalidates the request, book, session, and coroutine state before
  allocating a preload WebView.
- Once native chapter preparation finishes, the WebLayer starts the neighbour
  WebView on the next display frame. Do not add a fixed post-activation delay here:
  the frame boundary already protects the newly committed page, while another delay
  directly reduces the time available before the reader reaches the chapter edge.
- Prefetch candidates are truncated to the WebLayer's actual capacity. A one-slot
  device keeps the forward candidate and never parses a backward candidate merely
  to evict the forward WebView.
- `PageTurnBoundary` owns the boundary-transition flag as well as the target edge.
  Every tap, swipe, key, or menu path that exhausts the current page range must carry
  that flag so a backward turn activates the previous chapter at its final page.
- A logical chapter's end fragment is accepted only when the following logical
  chapter belongs to the same XHTML document. Identical ids in another document
  must never clip the current chapter.
- Logical chapters sharing one XHTML document retain every publisher node and are
  exposed through relative page windows. Missing starts and reversed ranges fail
  open to the intact document instead of deleting content. This preserves
  `body > ...`, `:first-child`, shared wrappers, backgrounds, scripts, and fixed
  running headers exactly as authored.
- Layout classification only consumes stylesheets that can be active on a screen.
  Print, speech, unknown media types, alternate or disabled links, disabled style
  elements, and imports owned by inactive style elements must not influence fixed or
  publisher-layout heuristics. Feature-only and applicable negated queries remain
  conservative classification inputs; rendering XHTML and its media attributes stay
  unchanged.
- Reflowable reader CSS uses low-specificity `:where(...)` fallbacks for paragraph
  spacing, media bounds, tables, and wrapping. Do not restore a blanket important
  line-height rule or universal inherited `box-sizing`: either can deform publisher
  title bars, pseudo-elements, SVG, and top-of-chapter decoration.
- Publisher-styled pagination keeps the reference engine's authoritative root/body
  box, margin, viewport, overflow, and column rules. Runtime wrapper normalization
  is reflowable-only and must never change publisher-styled height, overflow, or
  transforms. Do not reintroduce implicit publisher-canvas scaling in JavaScript.
- Link resolution runs on IO and maps arbitrary `id`, EPUB 2 `name`, and `xml:id`
  targets to the containing logical chapter by DOM order. The target fragment is
  applied to the candidate WebView before viewport and visual-state verification,
  so the chapter start is never briefly exposed first.
- An EPUB TOC parent that has both child entries and its own readable href exposes
  two actions: tapping the title opens that logical chapter, while the disclosure
  icon only expands or collapses its children. Structural `skip:` parents retain
  toggle-only behavior.
- Chapter normalization preserves a readable parent when its href and the first
  child's href share an XHTML document but identify different anchors. Only an
  exactly duplicated parent URL becomes structural; stripping fragments here would
  erase valid logical chapters and make their TOC rows impossible to navigate.
- This chapter-normalization change advances the EPUB core disk schema to version 5.
  Existing derived chapter and layout caches therefore rebuild once, so books
  imported under the old fragment-stripping rule do not retain stale `skip:` rows.
- Explicit TOC navigation also submits the logical chapter's start fragment during
  candidate activation. The logical page window remains primary, while the
  fragment target prevents a fail-open boundary calculation from displaying the
  physical XHTML start. Progress restoration never applies this fallback.
- The TOC activity result carries chapter href and start fragment in addition to the
  index. Same-chapter Direct navigation first calls `goToFragment`; a missing target
  reloads the candidate with the explicit fragment instead of taking the generic
  same-index page-edge shortcut.
- Directory, progress, search, internal-link, and chapter-boundary navigation share
  one pending target. A layout or lifecycle reload that arrives before the candidate
  commits must retain that target instead of falling back to the last persisted
  `ReadBook.durChapterIndex`.
- A zero-sized reader queues exactly one layout callback. Newer requests replace its
  action while retaining the complete chapter, edge, and fragment target; an older
  layout callback must never restart a superseded chapter load.
- Direct WebView chapter boundaries enter the Direct candidate transaction
  immediately. The synthetic loading-page insertion and replacement transaction is
  owned by the legacy Canvas page list and must never be used while Direct mode is
  active.
- Candidate activation distinguishes a stable target page from a target that is actually
  visible in the WebView viewport. Content-bearing chapter and fragment navigation must
  pass both checks; required navigation never bypasses the viewport probe. The activation
  timeout may replace a missing visual-state callback only after that viewport has already
  been verified. Empty logical chapters remain the sole renderability exception. A
  replacement request invalidates an older staged candidate before preparing its own
  WebView, so stale readiness cannot clear the new navigation transaction.
- Moting remains the Direct engine architecture baseline: publisher XHTML and resources
  stay in WebView, and current/standby WebViews provide chapter handoff. Direct mode must
  not downgrade publisher content to a Canvas body. This branch keeps its additional
  Range, cache, path-recovery, and font-deobfuscation support on top of that baseline.
- Same-document TOC and progress jumps keep a source snapshot mounted until Chromium
  reports the requested page and viewport content after compositor frames. A failed probe
  restores the source page instead of publishing a logical page number over a blank frame.
  A newer jump cancels the older handoff and its restore timers; only the latest request may
  move or commit the WebView. Source snapshots are removed after a failed jump only when the
  restored source viewport itself passes the same content checks.
  RTL horizontal pagination detects the browser's negative/default/reverse scroll model at
  runtime; it must not infer that model from a zero `scrollLeft` value.
- Horizontal reflow rebuilds a document-root extent marker from the complete physical page
  count. This compensates only Chromium's missing trailing column padding; it must not
  change body padding, column width, publisher CSS, or logical fragment-window page counts.
- Late images, stylesheets, media, frames, and fonts schedule bounded repagination. The
  runtime carries a visible DOM anchor across that refresh, with proportional page mapping
  used only when the anchor no longer exists.
- Memory trimming may discard background preloads and the warm previous WebView,
  but it must retain the current-generation foreground candidate even while the old
  committed chapter remains visible. This is required for TOC and search navigation,
  because returning from their activities can overlap `TRIM_MEMORY_UI_HIDDEN`.
- A session retained by the activity is reused only while it is open. If a lifecycle
  owner has already closed it, the next foreground request opens a fresh session
  before chapter preparation instead of surfacing `EPUB direct session is closed`.
- Every foreground Direct request remains a Direct transaction. Preparation or
  activation failure keeps the committed Direct page mounted; an initial failure
  displays the error overlay without invoking a second EPUB renderer.
- The index returned by `TocActivity` belongs to the persisted database chapter list;
  it is not a facade or spine identity. Direct preparation first loads that complete
  `BookChapter`, then normalizes it against the current facade by book URL, physical
  XHTML href, and start fragment. Index is only the final fallback. This keeps old
  database chapter lists navigable after parser rules or derived caches change.
- A same-document fragment is considered successful only after compositor frames
  report the expected page. Ordinary Direct turns likewise reconcile native state
  with runtime metrics; an unavailable runtime restores the previous native page
  instead of reporting progress for a page that was never displayed.
- Runtime stability waits for document images, fonts, and a bounded set of CSS
  background images. Background discovery scans at most 4,000 elements and 48
  unique URLs in roughly 8 ms animation-frame slices. The 1.6-second stability
  ceiling starts before discovery, so a large DOM cannot hide synchronous scan
  time outside the deadline. Reaching that ceiling sets layout `ready` only. The
  resource promises continue and report stability again after setting
  `resourcesReady`; external stylesheets settle before font and background probing,
  and a timed-out or failed preload must not enter the ready cache in between. A
  preload enters that cache only after `layoutPending` is false and a second metrics
  sample following the visual-state barrier confirms a final renderable revision.
- A second turn is queued while the current source-overlay animation is waiting or
  running. The target deadline is 900 ms: timeout restores the source DOM page under the
  still-mounted committed snapshot and does not start an alternate transition. Once the
  busy state clears, the original queued request is retried without self-feeding a copy
  back into the queue.
- Once a normal horizontal swipe wins gesture arbitration, the native layer sends
  `ACTION_CANCEL` to Chromium before subsequent move events. The first qualifying
  move remains available to the runtime so a direction-capable embedded scroller
  can claim the gesture; forms, media, galleries, and active nested scrollers keep
  their existing priority. A recognized drag that does not turn a page, or reaches
  a book boundary, snaps the document back to the committed page so Chromium cannot
  leave a partially scrolled column visible.
- `HEAD` interception uses metadata-only resource responses and never opens a body
  stream. Disk extraction is requested only for non-zero or suffix byte ranges;
  ordinary full and zero-start requests continue streaming directly.
- Animation overlays own one immutable source snapshot until their final frame is
  drawn. Timeout rollback restores the verified source page, live-target translation
  resets on every finish/cancel/lifecycle path, and bitmap release belongs to the
  overlay owner rather than asynchronous callbacks.
- Disk range-cache extraction is canceled on Facade close, but the ZIP archive is
  closed only after the extraction executor drains; this handoff is asynchronous
  and does not block the UI thread.
- `EpubReadView` owns only the visible Direct layer plus loading/error overlays. It
  must not regain Canvas display lists, decoded-image invalidation callbacks, or
  hidden selection/layout sessions.
- A failed or timed-out same-document handoff must remove its transition snapshot
  after restoring the source viewport. Keeping that bitmap mounted indefinitely can
  hide publisher images, backgrounds, or fonts that Chromium decodes later.
- Resource rendering acceptance is chapter-specific. After reusing one session for
  a directory jump, the active WebView URL must identify the target XHTML and that
  WebView must independently request and paint its image, nested CSS background,
  and font. A visible old/standby WebView or a request log alone is not proof.

## Test Matrix

Run the JVM EPUB suites after parser, layout, resource, gesture, cache, or lifecycle
changes:

```powershell
$env:JAVA_HOME='C:\Users\Admin\.cache\codex-android-build\jdk17'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx6g'
[Environment]::SetEnvironmentVariable(
  'ORG_GRADLE_PROJECT_kotlin.daemon.jvmargs', '-Xmx6g', 'Process'
)
.\gradlew.bat :app:testAppReleaseUnitTest `
  --tests 'io.legado.app.constant.PageAnimationSpeedTest' `
  --tests 'io.legado.app.help.book.EpubContentCachePolicyTest' `
  --tests 'io.legado.app.help.config.EpubReadEnginePolicyTest' `
  --tests 'io.legado.app.model.localBook.epubcore.*' `
  --tests 'io.legado.app.ui.book.read.ReadBookStartupPolicyTest' `
  --tests 'io.legado.app.ui.book.read.epub.*' `
  --no-daemon --no-parallel --max-workers=1
.\gradlew.bat :app:compileAppReleaseKotlin `
  --no-daemon --no-parallel --max-workers=1
node --check app/src/main/assets/epub/direct-runtime.js
powershell -ExecutionPolicy Bypass -File `
  output/playwright/run-epub-fragment-window-probe.ps1
powershell -ExecutionPolicy Bypass -File `
  output/playwright/run-epub-fragment-direction-probe.ps1
powershell -ExecutionPolicy Bypass -File `
  output/playwright/run-epub-fragment-renderability-probe.ps1
powershell -ExecutionPolicy Bypass -File `
  output/playwright/run-epub-wrapper-reflow-probe.ps1
powershell -ExecutionPolicy Bypass -File `
  output/playwright/run-epub-resource-readiness-probe.ps1
powershell -ExecutionPolicy Bypass -File `
  output/playwright/run-epub-annotation-probe.ps1
.\gradlew.bat :app:connectedAppDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.ui.book.read.epub.EpubDirectWebResourceRenderTest `
  --no-daemon --no-parallel --max-workers=1
```

The current large K2 source set exhausted the default 3 GiB Gradle heap during a
full recompile on 2026-08-12. The command-local 6 GiB override above is intentional;
do not raise the repository default without profiling CI and developer machines.
On the constrained Windows build host on 2026-09-08, a 6 GiB JVM exceeded available
commit memory. The ordinary-text release uses a command-local 4 GiB heap, SerialGC,
two active processors, one Gradle worker and in-process Kotlin; test workers are
limited to 512 MiB. Rebuildable Gradle caches were relocated to D: behind a junction
at their original path after C: filled during classpath snapshot generation.
Avoid `--rerun-tasks` for a focused test. It invalidates the entire Android/KSP
pipeline and forces Kotlin 2.3 to optimize all large coroutine state machines again;
use a normal task invocation or the project `clean` task only when a clean build is
actually required.

Required real-device coverage:

- Android System WebView current and one older supported version.
- At least one low-RAM device and one vendor-modified WebView device.
- LTR, RTL, `vertical-rl`, and `vertical-lr` books.
- Reflowable prose, image-only pages, fixed layout, title pages, Duokan gallery,
  audio/video, footnotes, SVG, tables, custom fonts, and scripted content.
- Same-document and cross-document EPUB 3 noterefs, encoded fragments, hidden
  footnote/endnote targets, nested notes, backlinks, long scrolling notes, relative
  note images, missing-target fallback, and immediate Android Back dismissal.
- For every annotation path, record the page before opening and after closing; the
  committed chapter/page and pagination revision must remain unchanged.
- Tap, short/long horizontal swipe, diagonal swipe, vertical scroll, nested scroll,
  selection, image zoom, form/media interaction, and chapter boundaries.
- Restore after rotation, font/indent changes, background/foreground, renderer loss,
  and process recreation.
- Production Direct-layer resource fixture: preserved publisher `<base>`, ordinary
  image, external and nested CSS background, embedded font, encoded Unicode/space
  paths, and a second chapter opened through the same session. Assert DOM state and
  actual pixels from the uniquely identified target WebView, then verify failed
  handoff snapshots are removed.
- Encoded fragment ids, duplicate TOC paths, malformed OPF values, deep CSS imports,
  missing CSS, case-mismatched resources, `xml:base`, CSS BOM/charset, IDPF/Adobe
  obfuscated fonts, range requests, and large resources.

## Known Gaps and Next Work

1. The JVM complete-ZIP fixtures cover container, OPF/NAV `xml:base`, canonical
   and encoded Unicode paths, fixed-layout classification, preserved HTML `<base>`,
   XHTML resource rewriting, CSS imports, images, SVG, and IDPF/Adobe-obfuscated
   fonts through `EpubDirectResourceFactory`. Still verify combined root `xml:base`
   plus HTML `<base>` precedence against real EPUB fixtures in Chromium before
   changing resolution.
2. Implement orientation requests and synthetic spread rendering only with a clear
   host-activity contract; parsing metadata alone is not feature completion.
3. Verify every memory-pressure level on low-RAM devices and keep the visible page
   mounted while preload and snapshot state is discarded.
4. Build a checked-in, license-safe EPUB fixture corpus for WebView instrumentation.
5. Profile the shared classification/document-preparation DOM on multi-megabyte
   chapters and adjust source-cache limits only with measured peak-memory evidence.
6. Verify TOC replacement, horizontal swipe arbitration, fixed-header composition,
   and turn-animation flicker on real Android System WebView builds before claiming
   the user-visible failures are fully resolved.
7. The chapter-specific Direct WebView resource instrumentation test builds locally,
   but no physical device was connected on 2026-08-14 or 2026-08-15. Do not mark the
   reported image/background/font failure resolved until that test passes on the
   affected device or an equivalent vendor WebView device. A user-requested R2 build
   remains explicitly device-unverified even after static, fixture, release, and
   remote-byte verification pass.
8. The affected device progressed from `ExceptionInInitializerError` to
   `NoClassDefFoundError`, consistent with a class left erroneous by its first failed
   initializer. Release analysis found no missing Direct class or referenced-only app
   class. The initialization-containment release `3.26.08150654 (29779134)` passed 248
   EPUB JVM tests, release lint, clean assembly, signature/ABI/DEX inspection, and R2
   byte-for-byte verification. It must still be opened against the same books on that
   device before this incident is marked resolved.

## Change Checklist

- Keep the change inside EPUB ownership boundaries.
- Add a focused unit test for every parser, classifier, policy, cache, or URL rule.
- Keep runtime template placeholders in sync with `runtimeScript()` and run
  `node --check` after editing the JavaScript asset.
- Malformed content may fail open inside Direct parsing or resource handling, but it
  must not switch to another renderer.
- Re-run all EPUB JVM suites and release compilation.
- Before a local Git checkpoint, run `git diff --check`, stage only an explicit
  EPUB dependency list, inspect `git diff --cached`, and never use `git add -A` in
  a mixed worktree.
- For touch, rendering, animation, media, or recovery changes, record real-device
  results here before claiming completion.
- Update the alignment table, budgets, and known gaps whenever behavior changes.


## 软件气泡尺寸修复交付：3.26.09090108

修复普通正文使用 EPUB 排版时软件气泡偏小的问题。此前整张内置 64×64 气泡图被缩为一个字高，图中数字只占画布的 15/64。现在软件气泡以 `1.5556em` 定宽，并应用气泡包的 0.5–1.5 倍缩放。这个基准参考原生旧 Android 段尾气泡的一个汉字宽 × 1.5556；不同 Android、字体和原生图片模式的尺寸并非完全相同。

气泡标志来自实际的软件渲染结果，并随资源落盘、状态查询保留。软件气泡替换原图时同时替换原标签宽高，修复固定 px 尺寸导致气泡包缩放无效的问题；重试返回普通图或默认倍率时清除旧缩放。普通原始 TEXT/text 图片继续使用原有尺寸规则。自定义模板保留图形比例，高度限制在同一气泡框内，避免纵向模板把一行撑成大块空白。此次变更不增加图片下载、书源脚本执行或新的渲染轮询。

正文字符、目录锚点、原始 click、段落规则和 pclick 隔离及会话取消代码保持现状。此前导致图片类初始化失败的正则转义修复继续保留；最终 APK 中提取出的实际正则再次通过 Windows 原生 ICU 的 24 项匹配检查及旧模式负控。

桌面 Edge 重新执行了 36 项气泡尺寸场景，以及 15 项异步图片、2 种图片交互模式、6 种正文样例和 6 组 EPUB 回归。报告与当前生产输入、运行时及测试产物的 SHA-256 绑定；原生图片服务和书源执行使用模拟，不能代替 Android WebView 或真机测试。

Gradle 相关测试、全量 Lint 和签名构建成功，用时 `41m 52s`。JVM 共 565 项，563 项通过，2 项既有 Currency 样例探针跳过；无失败或错误。Lint 无新增错误，沿用 142 项已审计历史错误基线，另有 1523 项历史警告，本轮未新增警告。2,616 项清单内输入在构建前后及上传前核对一致。独立审查另补查了清单外的 9 项 Gradle 配置和 Cronet 依赖，二进制与 Git HEAD 字节一致，文本除 CRLF 外一致，且在包检查与上传前重新核对其 SHA-256。这份补充证据是构建期间的审计，不作为事前冻结，也不将清单称为所有构建依赖的完整枚举。

| 交付项 | 验证结果 |
| --- | --- |
| 版本 | `3.26.09090108`（`29814788`） |
| 应用 / ABI | `io.legado.app.Archive`，仅 `arm64-v8a` |
| APK 大小 | 40,611,825 字节 |
| APK SHA-256 | `8bfd883dfc6d1cd6bd08f64ed2d1de17b9a21d3069ba180992161fea925f4343` |
| 签名证书 SHA-256 | `d6441f18d3413a81c0e82e51f550c558c5d79213e921b02dec30bd3473d4f2b6` |
| R2 对象 | `legado-arm64-v8a.apk`、`latest.json` |
| R2 更新时间 | `2026-09-08T18:23:10Z` |

安装包为 `output/Legado-3.26.09090108-29814788-arm64-v8a-release.apk`。签名与此前版本一致，可覆盖安装，无需清除书籍数据。R2 上传后完整回读 APK，字节数和 SHA-256 与本地一致。

没有可用 Android 真机、模拟器或 ART，未声称完成设备验证；默认 PNG 的原有 64px 取样策略未在本轮改变。旧发布证据与修改前快照保留。主要证据为 `output/text-bubble-size-source-audit-20260909.md`、`output/text-bubble-size-browser-verification.json`、`output/text-bubble-size-build-inputs.json`、`output/text-bubble-size-jvm-results.json`、`output/text-bubble-size-apk-verification.json`、`output/text-bubble-size-r2-verification.json`。

## 翻页手感与 Reeden 高亮规则（2026-09-13）

交互式翻页收尾使用五次 Hermite 曲线，将松手速度转换为初始斜率，随后平滑减速；起始加速度、终点速度和终点加速度为零。极快滑动的斜率受限，避免越过目标位置。`ValueAnimator` 使用线性时间进度，交互收尾只应用一次插值；自动翻页继续使用原有样式曲线。仿真收尾不超过基础时长的 1.2 倍，快速甩动最低 72 ms，四档速度继续生效。覆盖层随页面同步 `invalidate()`，保留此前修复的同帧对齐行为。

高亮规则由 `help/book/highlight` 中的解析、匹配、样式和文件存储组件管理，在 Direct 文档分页前装饰正文；普通正文及 EPUB 共用实现。装饰保留原文字、段落偏移、链接、内联强调和图片，跨段匹配按原 UTF-16 偏移切分。完整段落的 `display:inline` 气泡包裹在 span 内，避免相邻段落并成一行。高亮资源由本地会话路由提供，背景图和 `border-image-source` 一起参与分页前资源就绪检查。高亮修订号进入文档/原生排版缓存键，从管理页返回时刷新并保留阅读位置。

| 范围 | 当前行为 |
| --- | --- |
| Reeden 容器 | .red 文件导入；RED01 内部 gzip JSON 由解析器解码 |
| 匹配 | 普通文字、正则、跨行、标题、规则顺序；全局或本地书籍范围 |
| 图片样式 | 解码包内 gzip/base64 图片，按内容哈希保存；支持九宫格拉伸背景 |
| 原生正文 | 颜色、背景色、字号、粗斜体和基础文字装饰；复杂 CSS 图片气泡由 Direct/EPUB 承载 |
| 管理 | 阅读菜单“高亮规则”；沿用段落规则卡片，支持开关、编辑/预览、排序、范围、导出、备份恢复 |
| 限制 | 明确声明 AES-256-GCM 的封装暂未实现解码；不能仅凭 RED10 标签推断加密；未附带的 Reeden 字体回退到阅读字体 |

解析限制为 RED 文件 24 MiB、解码 JSON 48 MiB、单图片 8 MiB、总图片 32 MiB、2048 条规则。高亮管理的文件选择器只显示 .red 文件；用户提供的 7z 仅在开发环境解包取样，不新增高亮压缩包导入逻辑。匹配有时间/字符读取预算，避免失控回溯；超限匹配会保留已得到的结果。图片与规则索引原子写入，导入按规则身份去重，本书 Reeden ID 只作为元数据，不能误绑定同名的本地书。

用户提供的原始包有 12 个可读取高亮文件、4 个 RED10 加密文件和 1 个替换规则文件；可读取文件合计 125 条，去重为 70 条，59 条附图。原始包只作为本地验证夹具，不随 APK 分发。专项测试覆盖解码、资产、匹配、HTML 边界、作用域、去重、导出和备份恢复。完整本轮 JVM 回归共 747 项，745 项通过、2 项既有 Currency 探针跳过。桌面 Chromium 400×850 预览验证中文字序、嵌套强调、段落独立、图片解码和无水平溢出，两个修改过的运行时均通过 `node --check`。

签名、APK 字节检查与 R2 回读结果以 `output/reader-reeden-release-verification.json`、`output/reader-reeden-apk-bytes-verification.json` 和 `output/reader-reeden-r2-verification.json` 为准。未连接 Android 真机，当前主机也无法运行模拟器；本轮没有执行 Android instrumentation/ART，不把 JVM 或桌面渲染结果视为实际触控手感验收。后续设备验收应包括连续快速翻页、慢拖/反向回弹、仿真收尾、图片气泡跨页、不同字体/夜间模式和管理页返回后的阅读位置。

### 本地导入、两端对齐与图片居中修正

高亮管理仅保留本地 `.red` 导入，删除网址入口及下载处理，导入器只接受 `content`、`file` URI。RED10 会先读取实际文件头并验证长度和 nonce；只有明确声明 `aes-256-gcm-manifest` 才报告加密封装，其他未知编码与损坏文件分别提示。原始样例中的四个文件确实作出该算法声明，这不代表用户或作者设置了密码。

水滴、荷花样例的背景为 `100% 100%` 拉伸图片，没有九宫格声明。Chromium 的行内 `box-decoration-break:clone` 配合非零左右内边距会破坏两端对齐，而且上下内边距不参与行高。修正后的图片高亮将完整图片绘制到 `border-image` 的外扩区域，使用 `0 fill` 保留整幅图片；原有明确的九宫格声明继续生效。独立的行内间隔元素只在匹配的首尾占位，使用 `slice`，使正文正常参与字距分配。已有页面留白可容纳装饰时，连续行与普通正文保持相同文字边界；窄边距时只补足不足的空间。上下装饰计入行高，长引用仍可在行和页面之间断开，保留原段落、文字偏移、强调、图片和链接。

预览沿用当前阅读的两端对齐设置。含上述装饰的段落不使用纯文字行网格，以免覆盖背景所需的行高。没有增加翻页时的 JavaScript 排版操作。

行内短评图片放在按字体基线对齐的 flex 元素内，以不可见的 CSS 全角空格提供中文字体的基线和行框，不向正文插入字符。图片在该行框内垂直居中；原点击链接、异步图片、三档气泡缩放和高宽限制保持原有协议。

最终代码的 JVM 回归为 755 项，753 项通过、2 项既有跳过；独立高亮及原始文件检查为 59 项。桌面 Chromium 使用当前生产构建生成的页面，验证 95 组高亮布局及 8 组图片组合（48 个气泡），覆盖两端对齐、不同字号、窄边距、长对话跨页、原文偏移及装饰边界。三个实际浏览器点击各触发一次原有短评动作。证据见 `output/reader-reeden-layout-browser-verification.json`、`output/reader-reeden-layout-click-verification.json`；本次签名和发布记录使用 `reader-reeden-local` 前缀。尚未执行 Android 真机、instrumentation 或 ART 验证。

### 分区图片的端部比例修正

“简约白白兔”等规则明确指定图片的可拉伸区域。此前将文字的 `padding` 直接用作四边的目标绘制宽度，把约半张原图压成十几像素的窄条，导致兔耳、爱心等端部图案几乎不可见；中间仅一像素的区域则被独立拉高，造成白底过大。图片文件本身能够解码，并非资源下载失败。上一轮水滴、荷花用例没有分区参数，未覆盖这一错误。

九宫格现在使用 `border-image-width:auto`，以原始切片的固有尺寸为依据，空间不足时四边统一缩放。文字留白仍按规则提供的参数处理，不能充当图片切片的尺寸。没有分区参数的整图样式继续按原规则拉伸。此修正不改变原图、导入数据、翻页曲线或行内图片尺寸协议。

验证扩大到原始文件中的 55 条行内图片高亮，包括 23 条带分区参数的规则。364 组桌面 Chromium 场景检查全部图片解码、正文和预览、两端对齐、字体变化、窄边距和跨页布局，另对 23 条规则各取长短两句，将截图与原图分区的独立 Canvas 绘制结果比对，允许一像素取整及抗锯齿差异。白白兔的端部图案与白底高度单独检查；修正前的页面作为会失败的对照。发布证据使用 `reader-reeden-artwork` 前缀。没有 Android 设备验证，不以桌面像素结果代替 Android WebView 验收。

补充修正段首、段尾的额外空白：图片高亮的间隔元素只在段落内部保留与相邻文字之间的距离，段落边界不再把图片内边距叠加到首行缩进或行尾。原有首行缩进、文字、空白字符和匹配偏移不变。布局验证为每个段落检查第一个字符的位置，并检查完整引用的续行左边界和非末行右边界；增加普通中文段落、中文引号、两种直角引号和英文引号的对照。专项原始规则测试增至 60 项，工程回归增至 756 项。`reader-reeden-artwork-aligned` 记录用于最终布局验收，之前未包含段首修正的测试记录保留。

### 高亮管理搜索与分页留白

管理页增加名称、分组、匹配内容的即时文字搜索，保留编辑返回后的查询，显示结果数量和空状态。搜索按普通文字匹配，不执行用户输入的正则；筛选时可选择导出全部或当前结果。拖动只交换当前结果在原列表中占据的位置，隐藏规则保持原位置。保存编辑内容保留当前排序；开关和作用域读取最新规则再修改，已删除规则不能通过旧编辑页复活。加载、保存和拖动期间协调刷新，避免旧结果覆盖新状态。预览生成在后台执行并取消旧任务。

导出逐条写入 gzip JSON，限制数量、UTF-8 内容和压缩后大小，避免生成超过自身导入上限的文件；背景图缺失或哈希不符时明确报告。再次导入相同规则会修复其缺失或损坏的图片，保留现有开关和排序。导入仍限本地 `.red`。

整图拉伸会把透明边距按每行宽度缩放，造成多行高亮的短末行看起来向左突出。无拉伸区参数且已知图片尺寸的标准行内高亮，改为保护两侧窄端部的比例，中间保持横向拉伸；完整源图及柔和阴影保留。已有九宫格参数继续按原定义绘制。没有修改原始图片、正文字符、字距或匹配边界。

普通 EPUB 分页按浏览器的 1/64 CSS 像素单位向下计算行距，消除小数进位挤掉整行的问题；微调不超过 2 CSS 像素。标准图片高亮单独按自身所需行高计算，含图片的章节不再整章跳过普通段落。普通正文页的少量余量可分摊到该页已有段落间，单个间隔最多增加半行且不超过 16 CSS 像素。如果任一文字行的分页归属改变，就撤回段距补齐。章末、滚动模式、出版社固定排版、标题/插图等复杂页面保留自然布局。计算限于布局刷新，选择文本期间继续延后刷新；不拆分文字节点，也不在翻页动画中执行重排。

段落识别和图片行高先集中读取，再统一写回属性，避免每处理一个段落就触发整章多栏重排。段距补齐设有段落、节点和行数预算，超长章节保留基本行距修正；重复刷新不会累计段距。

本轮证据使用 `reader-reeden-manager` 前缀。签名构建、JVM、Lint、原始文件回归、桌面浏览器实际排版及 R2 完整回读分别记录；无可用 Android 设备，不能将桌面测量视作 Android WebView 或触控手感验收。

### 按实际行位置补齐底部与共用阅读素材

`reader-resources` 这一轮替代前述仅补段距的方案。原生阅读的 `TextPage.upLinesPosition()` 按行序号分配页面余量；仅调整 EPUB 段距无法处理跨页的长段落，图片装饰的高度也会让每页剩余空间不同。共用的 `page-alignment.js` 在分页和字体、图片加载完成后测量实际文字行，保留原换行与横向位置，将余量逐行加到绘制位置。高亮的 `border-image-outset` 一同计入底边，因此正文与装饰不会伸出有效阅读区域。字号、字宽和原有两端对齐不缩放。

Direct 与模板分页都使用这个组件，受原有“底部对齐”开关控制。接近满页的章节末页同样补齐，至少还能容纳一整行的短末页保持自然高度。滚动、竖排、RTL、出版社/固定排版保持原布局；图片、表格、ruby 等复杂段落不拆行，可移动的普通文字不能跨过它们。模板可用 `data-reader-bottom-align="false"` 关闭某个正文区域的补齐。操作仅在重排时执行，保留 16,000 行预算。

每行保留内联祖先样式；重排前恢复原节点，阅读锚点按段落和 UTF-16 偏移重新解析，选中文字期间延后重排。跨行裁剪不会生成空的高亮边框或重复链接 ID，段落末尾锚点保留准确偏移。字体和素材修订号进入排版缓存，编辑返回后会重新测量。

共用素材库入口位于高亮管理和 EPUB 页面管理。界面提供搜索、图片/字体/文件夹筛选、图片缩略图、字体预览、重命名、导出、复制引用和已保存引用查询。本地来源文件夹可逐级浏览、按当前筛选批量导入；导入的是应用内副本，移动原文件或移除来源目录不会使已保存引用失效。删除前检查已保存的规则和模板，草稿不计入检查。选择器显示当前选择，并提供“跟随阅读字体”或“不使用背景图片”。

高亮字体选择仅替换 `font-family`，清理重复声明，保留其他 CSS（包括字符串和 data URL 内的分号）。字体 CSS 与普通文字颜色可以同时生效。模板选择器只替换自身标记的字体/背景 CSS 段，保留作者编写的 HTML、CSS 和脚本。高级用法可复制 `https://reader-assets.epub.local/<sha256>` 图片引用或 `legado-resource-<sha256>` 字体族；该虚拟域名由 WebView 本地拦截，不发往互联网。

素材按内容 SHA-256 去重。图片支持 PNG/JPG/GIF/WebP，字体支持 TTF/OTF/TTC/WOFF/WOFF2；单个文件不超过 64 MiB，库内不超过 2048 项和 512 MiB，最多保存 32 个来源文件夹。PNG 验证分块长度和 CRC，字体验证表边界，导入、恢复、导出核对类型、尺寸及哈希。相同文件再次导入可修复损坏副本。原生字体绘制支持系统可解码的 TTF/OTF/TTC，WOFF 系列用于 WebView，原生显示回退到阅读字体。

RED01 保留原字段，可附加引用的素材；模板 ZIP 将引用素材与原模板 JSON 一同打包。导入先验证整包引用、容量、格式和校验值，再提交，缺失引用会明确报错。应用备份包含共用素材与高亮文件；恢复日志同时覆盖这两个目录，失败回滚后清除相应缓存。设备上的来源目录授权不写入可移植备份。高亮规则仍仅支持本地 `.red` 导入，没有网址或 `.7z` 导入入口。

验证使用本轮生产 Kotlin 生成的 HTML、用户原始规则图片、当前运行时，以及实际加载的本地测试字体；字体文件不随 APK 分发。JVM 测试、浏览器逐页几何/像素/选择定位检查、Lint、签名与 DEX 检查、发布回读分别使用 `output/reader-resources-*` 记录。Android UI、WebView、ART 与实际触控尚未在设备上验收。
