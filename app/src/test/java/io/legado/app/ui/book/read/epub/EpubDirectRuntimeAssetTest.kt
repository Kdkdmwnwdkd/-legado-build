package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EpubDirectRuntimeAssetTest {

    @Test
    fun `position reports do not scan renderable content`() {
        val source = runtimeSource()
        val reportBody = functionBody(source, "report")
        val setPageBody = functionBody(source, "setPage")

        assertTrue("positionMetrics()" in reportBody)
        assertFalse("metrics()" in reportBody)
        assertTrue("positionMetrics()" in setPageBody)
        assertTrue("renderable:computeRenderableContent()" in source)
    }

    @Test
    fun `fragment pagination keeps the publisher dom and exposes relative pages`() {
        val source = runtimeSource()
        val windowBody = functionBody(source, "applyPageWindow")
        val pageBody = functionBody(source, "setPage")
        val horizontalBody = functionBody(source, "setHorizontalScrollPage")
        val fragmentBody = functionBody(source, "goToFragment")
        val renderableBody = functionBody(source, "computeRenderableContent")
        val pageCountBody = functionBody(source, "computePageCount")

        assertFalse("extractContents()" in source)
        assertFalse("root.removeChild" in source)
        assertTrue("windowEndPage-windowStartPage" in windowBody)
        assertTrue("windowStartPage+currentPage" in pageBody)
        assertTrue("windowStartPage+currentPage" in horizontalBody)
        assertTrue("setHorizontalScrollOffset(scrolling,targetOffset)" in horizontalBody)
        assertFalse("root.style.setProperty('transform'" in pageBody)
        assertTrue("targetInsideFragmentWindow" in fragmentBody)
        assertTrue("if(page<0)return false" in fragmentBody)
        assertTrue("rectInsidePageWindow" in renderableBody)
        assertTrue("viewportAnchored" in pageCountBody)
    }

    @Test
    fun `reader chrome is page local transparent and excluded from document layout`() {
        val source = runtimeSource()
        val slotBody = functionBody(source, "readerChromeSlot")
        val ensureBody = functionBody(source, "ensureReaderChrome")
        val positionBody = functionBody(source, "positionReaderChrome")
        val visibilityBody = functionBody(source, "updateReaderChromeVisibility")
        val anchoredBody = functionBody(source, "viewportAnchored")
        val mutationBody = functionBody(source, "isReaderChromeMutation")

        assertTrue("root.appendChild(readerChromeLayer)" in ensureBody)
        assertTrue("position:absolute!important" in ensureBody)
        assertTrue("position:absolute!important" in slotBody)
        assertFalse("position:fixed" in ensureBody + slotBody)
        assertFalse("position:sticky" in ensureBody + slotBody)
        assertTrue("background:transparent!important" in slotBody)
        assertTrue("background:transparent!important" in ensureBody)
        assertTrue("box-shadow:none!important" in slotBody)
        assertTrue("box-shadow:none!important" in ensureBody)
        assertTrue("span.style.cssText" in slotBody)
        assertTrue("background:transparent!important" in slotBody.substringAfter("span.style.cssText"))
        assertTrue("pointer-events:none!important" in ensureBody)
        assertTrue("user-select:none!important" in slotBody)
        assertTrue("windowStartPage+Math.max(0,Number(currentPage)||0)" in positionBody)
        assertTrue("layoutRtl?-page*extent:page*extent" in positionBody)
        assertTrue("readerChromeData&&readerChromeData.chapterFirstPage===true||currentPage===0" in visibilityBody)
        assertTrue("readerChromeHideHeaderOnFirstPage&&first?'hidden':'visible'" in visibilityBody)
        assertTrue("readerChromeFooter.style.setProperty('visibility','visible','important')" in visibilityBody)
        assertFalse("display:none" in visibilityBody)
        assertTrue("isReaderChromeNode(element)" in anchoredBody)
        assertTrue("nodes.every(isReaderChromeNode)" in mutationBody)
        assertTrue("if(isReaderChromeMutation(mutation))continue" in source)
        assertTrue("setReaderChrome:setReaderChrome" in source)
        assertTrue("setReaderChromeData:setReaderChromeData" in source)
    }

    @Test
    fun `reader chrome config updates are idempotent and data refresh does not rebuild nodes`() {
        val source = runtimeSource()
        val configBody = functionBody(source, "setReaderChrome")
        val dataBody = functionBody(source, "setReaderChromeData")

        assertTrue("readerChromeConfigSignature" in configBody)
        assertTrue("if(nextKey===readerChromeConfigKey)" in configBody)
        assertTrue("applyReaderChromeData();" in configBody)
        assertTrue("readerChromeConfigKey=nextKey" in configBody)
        assertTrue("readerChromeConfigKey=readerChromeConfigSignature()" in source)
        assertTrue("readerChromeData=data&&typeof data==='object'?data:{}" in dataBody)
        assertTrue("positionReaderChrome()" in dataBody)
        assertFalse("removeReaderChrome()" in dataBody)
        assertFalse("markLayoutDirty()" in dataBody)
        assertFalse("scheduleLayoutRefresh" in dataBody)
        assertFalse("flushLayoutRefresh" in dataBody)
    }

    @Test
    fun `reader chrome stays on the source page during horizontal page animation`() {
        val source = runtimeSource()
        val setPageBody = functionBody(source, "setPage")
        val horizontalBody = functionBody(source, "setHorizontalScrollPage")
        val positionBody = functionBody(source, "positionReaderChrome")
        val visibilityBody = functionBody(source, "updateReaderChromeVisibility")
        val commitAnchorBody = functionBody(source, "commitViewportAnchor")
        val targetStatement = "currentPage=Math.max(0,Math.min(m.pageCount-1,Number(index)||0));"
        val targetAssignment = setPageBody.indexOf(targetStatement)
        val horizontalStart = setPageBody.indexOf("var animating=setHorizontalScrollPage(animationMs)")

        assertTrue("readerChromeMotionPending=true" in horizontalBody)
        assertTrue("readerChromeMotionPending=false" in horizontalBody)
        assertTrue("readerChromeMotionPending" in positionBody)
        assertTrue("readerChromeMotionPending" in visibilityBody)
        assertTrue(targetAssignment >= 0)
        assertTrue(horizontalStart > targetAssignment)
        assertFalse(
            setPageBody.substring(targetAssignment + targetStatement.length)
                .trimStart()
                .startsWith("positionReaderChrome();")
        )
        assertTrue(
            horizontalStart <
                setPageBody.indexOf("if(!animating){positionReaderChrome();commitViewportAnchor();}")
        )
        assertTrue("committedViewportAnchor=captureViewportAnchor()" in commitAnchorBody)
    }

    @Test
    fun `read aloud follows exact visible cues without competing with selection`() {
        val source = runtimeSource()
        val buildIndex = functionBody(source, "buildReadAloudTextIndex")
        val normalizeCue = functionBody(source, "normalizeReadAloudCue")
        val nearestOccurrence = functionBody(source, "nearestReadAloudOccurrence")
        val follow = functionBody(source, "locateReadAloud")

        assertTrue("readAloudTextIndex=buildReadAloudTextIndex()" in source)
        assertTrue("[data-legado-runtime]" in source)
        assertTrue("#legado-epub-annotation-overlay" in source)
        assertTrue("#legado-epub-image-overlay" in source)
        assertTrue("for(var child=element.firstChild;child;child=child.nextSibling)visit(child)" in buildIndex)
        assertTrue("pendingSpace=true" in buildIndex)
        assertTrue("rawToNormalized" in normalizeCue)
        assertTrue("text.lastIndexOf(cue" in nearestOccurrence)
        assertTrue("text.indexOf(cue" in nearestOccurrence)
        assertTrue("if(selectionPageLock)return" in follow)
        assertTrue("target-cue.offset" in follow)
        assertTrue("readAloudDomPosition(index,occurrence+cue.offset)" in follow)
        assertTrue("pageForRect(rect)" in follow)
        assertFalse("setPage(" in follow)
        assertFalse("fallbackPageAfterLayout" in follow)
        assertTrue("locateReadAloud:locateReadAloud" in source)
        assertTrue("invalidateReadAloudTextIndex()" in source)
    }

    @Test
    fun `reader chrome participates in snapshot and adjacent frame identity`() {
        val webLayer = webLayerSource()
        val target = epubSource("EpubPageFrameTarget.kt")
        val renderer = epubSource("EpubVirtualPageRenderer.kt")
        val snapshotBody = kotlinFunctionBody(webLayer, "committedPageSnapshotKey")
        val metadataBody = kotlinFunctionBody(webLayer, "pageFrameMetadata")

        assertTrue("readerChromeGeometryKey = EpubPageFrameTarget.readerChromeGeometryKey" in snapshotBody)
        assertTrue("layoutSignature = EpubPageFrameTarget.layoutSignature" in metadataBody)
        assertTrue("frame.layoutSignature != layoutSignature" in target)
        assertTrue("frame.readerChromeContentRevision != readerChromeContentRevision" in target)
        assertTrue("renderLayer.updateReaderChromeData(readerChromeData)" in renderer)
        assertTrue("readerChromeContentRevision = request.readerChromeContentRevision" in renderer)
        assertFalse("resourcesReady" in metadataBody)
    }

    @Test
    fun `stale reader chrome animation frames cannot become the committed page snapshot`() {
        val webLayer = webLayerSource()
        val preserveBody = kotlinFunctionBody(
            webLayer,
            "preserveAnimationSourceForCurrentOrAdjacent"
        )

        assertTrue("frame.layoutSignature == EpubPageFrameTarget.layoutSignature(" in preserveBody)
        assertTrue(
            "frame.readerChromeContentRevision ==" in preserveBody &&
                "EpubPageFrameTarget.readerChromeContentRevision(" in preserveBody
        )
        assertTrue(
            preserveBody.indexOf("frame.readerChromeContentRevision ==") <
                preserveBody.indexOf("preserveBitmapAsCommittedSnapshot(")
        )
        assertTrue("adjacentPageFrames?.offerFrame(frame)" in preserveBody)
    }

    @Test
    fun `host menu visibility blocks and then invalidates direct snapshots`() {
        val webLayer = webLayerSource()
        val activity = readBookActivitySource()
        val menu = readMenuSource()
        val searchMenu = searchMenuSource()
        val searchMenuAnimation = kotlinFunctionBody(searchMenu, "initAnimation")
        val menuHideBody = kotlinFunctionBody(activity, "onMenuHide")
        val menuHiddenBody = kotlinFunctionBody(activity, "onMenuHidden")
        val releaseBody = kotlinFunctionBody(
            activity,
            "scheduleEpubHostOverlayCaptureRelease"
        )
        val captureBlockBody = kotlinFunctionBody(webLayer, "setHostOverlayCaptureBlocked")
        assertTrue("private var hostOverlayCaptureBlocked = false" in webLayer)
        assertTrue("hostOverlayCaptureBlocked ||" in webLayer)
        assertTrue("setHostOverlayCaptureBlocked(epubCoreActive)" in activity)
        assertTrue("setHostOverlayCaptureBlocked(false)" in activity)
        assertTrue("scheduleEpubHostOverlayCaptureRelease()" in menuHideBody)
        assertTrue("scheduleEpubHostOverlayCaptureRelease()" in menuHiddenBody)
        assertTrue("ViewCompat.requestApplyInsets(binding.root)" in releaseBody)
        assertTrue("binding.root.doOnPreDraw" in releaseBody)
        assertTrue("bottomDialog > 0" in releaseBody)
        assertTrue("scheduleCommittedPageSnapshotRefresh(\"host-overlay-hidden\")" in captureBlockBody)
        assertTrue("scheduleQueuedPageTurnDrain()" in captureBlockBody)
        assertTrue("binding.searchMenu.runMenuOut { exitSearchMenu() }" in activity)
        assertTrue("callBack.onMenuHidden()" in menu)
        assertTrue("this.visibility = INVISIBLE" in menu)
        assertTrue(menu.indexOf("this.visibility = INVISIBLE") < menu.indexOf("callBack.onMenuHidden()"))
        assertTrue("callBack.onMenuShow()" in kotlinFunctionBody(searchMenu, "runMenuIn"))
        assertTrue("callBack.onMenuHide()" in kotlinFunctionBody(searchMenu, "runMenuOut"))
        assertTrue("this@SearchMenu.invisible()" in searchMenuAnimation)
        assertTrue("callBack.onMenuHidden()" in searchMenuAnimation)
        assertTrue(
            searchMenuAnimation.indexOf("this@SearchMenu.invisible()") <
                searchMenuAnimation.indexOf("callBack.onMenuHidden()")
        )
    }

    @Test
    fun `horizontal pagination preserves the complete physical last-page extent`() {
        val source = runtimeSource()
        val pageCountBody = functionBody(source, "computePageCount")
        val mountBody = functionBody(source, "mountHorizontalExtentMarker")

        assertTrue("detachHorizontalExtentMarker()" in pageCountBody)
        assertTrue("mountHorizontalExtentMarker(cachedDocumentPageCount)" in pageCountBody)
        assertTrue("setHorizontalScrollOffset(docRoot,preservedHorizontalOffset)" in pageCountBody)
        assertTrue("document.documentElement" in mountBody)
        assertTrue("pages*extent-1" in mountBody)
        assertTrue("marker.style.setProperty('left'" in mountBody)
        assertTrue("marker.style.setProperty('right'" in mountBody)
        assertFalse("root.appendChild(marker)" in mountBody)
    }

    @Test
    fun `viewport readiness follows the displayed page geometry`() {
        val source = runtimeSource()
        val viewportBody = functionBody(source, "computeViewportRenderable")
        val viewportRectBody = functionBody(source, "viewportRect")
        val metricsBody = functionBody(source, "metrics")

        assertTrue("viewportRenderablePage===currentPage" in viewportBody)
        assertTrue("viewportRenderablePage=currentPage" in viewportBody)
        assertTrue("rect.right>.5&&rect.bottom>.5" in viewportRectBody)
        assertTrue("rect.left<window.innerWidth-.5&&rect.top<window.innerHeight-.5" in viewportRectBody)
        assertTrue("viewportAnchored" in viewportBody)
        assertTrue("checkViewport===true?computeViewportRenderable():false" in metricsBody)
    }

    @Test
    fun `horizontal wrapper normalization is bounded to wrapper chains`() {
        val source = runtimeSource()
        val normalizeBody = functionBody(source, "normalizeHorizontalReflow")
        val childBody = functionBody(source, "soleHorizontalReflowChild")

        assertTrue("horizontalReflowCandidateLimit=24" in source)
        assertTrue("horizontalReflowDepthLimit=8" in source)
        assertTrue("cursor<horizontalReflowCandidateLimit" in normalizeBody)
        assertTrue("entry.depth<horizontalReflowDepthLimit" in normalizeBody)
        assertTrue("soleHorizontalReflowChild(node)" in normalizeBody)
        assertTrue("publisherStyled" in normalizeBody.substringBefore("return"))
        assertFalse("querySelectorAll" in normalizeBody)
        assertTrue("candidate.nodeType===Node.TEXT_NODE" in childBody)
        assertTrue("if(child)return null" in childBody)
    }

    @Test
    fun `ordinary horizontal chapters clamp only the root vertical offset`() {
        val source = runtimeSource()
        val clampBody = functionBody(source, "clampHorizontalRootScroll")
        val pageBody = functionBody(source, "setHorizontalScrollPage")

        assertTrue("vertical||fixed||publisherStyled" in clampBody)
        assertTrue("document.scrollingElement||document.documentElement" in clampBody)
        assertTrue("node.scrollTop=0" in clampBody)
        assertFalse("querySelectorAll" in clampBody)
        assertTrue("clampHorizontalRootScroll()" in pageBody)
        assertTrue(
            "window.addEventListener('scroll',function(){updateReaderChromeForDisplayedPage();report();}" in source
        )
        assertFalse(
            "window.addEventListener('scroll',function(){clampHorizontalRootScroll();report();}" in source
        )
    }

    @Test
    fun `publisher layout is not transformed by invented runtime fitting`() {
        val source = runtimeSource()

        assertFalse("fitImplicitPublisherCanvas" in source)
        assertFalse("__LEGADO_IMPLICIT_SINGLE_PAGE__" in source)
    }

    @Test
    fun `rtl pages use detected browser scroll semantics`() {
        val source = runtimeSource()
        val detectBody = functionBody(source, "detectRtlScrollType")
        val readBody = functionBody(source, "horizontalScrollOffset")
        val writeBody = functionBody(source, "setHorizontalScrollOffset")

        assertTrue("probe.scrollLeft=1" in detectBody)
        assertTrue("'negative':'reverse'" in detectBody)
        assertTrue("case 'default':offset=max-raw" in readBody)
        assertTrue("default:offset=-raw" in readBody)
        assertTrue("case 'default':scrolling.scrollLeft=max-target" in writeBody)
        assertTrue("default:scrolling.scrollLeft=-target" in writeBody)
    }

    @Test
    fun `late publisher resources preserve a visible content anchor`() {
        val source = runtimeSource()
        val refreshBody = functionBody(source, "scheduleLayoutRefresh")
        val runRefreshBody = functionBody(source, "runLayoutRefresh")
        val resourceBody = functionBody(source, "layoutResourceEvent")
        val rememberBody = functionBody(source, "rememberViewportAnchor")
        val textAnchorBody = functionBody(source, "textAnchorAtPoint")
        val reportBody = functionBody(source, "report")
        val setPageBody = functionBody(source, "setPage")
        val commitAnchorBody = functionBody(source, "commitViewportAnchor")

        assertTrue("rememberViewportAnchor()" in refreshBody)
        assertTrue("activationBoundaryPage(nextPageCount)" in runRefreshBody)
        assertTrue("pageForViewportAnchor(anchor)" in runRefreshBody)
        assertTrue("committedViewportAnchor" in rememberBody)
        assertTrue("viewportRect(rectForViewportAnchor(anchor))" in textAnchorBody)
        assertTrue("committedViewportAnchor=captureViewportAnchor()" in reportBody)
        assertTrue("if(!animating){positionReaderChrome();commitViewportAnchor();}" in setPageBody)
        assertTrue("committedViewportAnchor=captureViewportAnchor()" in commitAnchorBody)
        assertTrue("if(!layoutRefreshInProgress&&isLayoutPending())" in commitAnchorBody)
        assertTrue("pendingLayoutAnchor=committedViewportAnchor" in commitAnchorBody)
        assertTrue("page:Math.max(0,displayedPageIndex())" in commitAnchorBody)
        assertTrue("scheduleLayoutRefresh(true)" in resourceBody)
        assertTrue("document.addEventListener('load',layoutResourceEvent,true)" in source)
        assertTrue("document.fonts.addEventListener('loadingdone'" in source)
    }

    @Test
    fun `reader font settles before initial stable while publisher resources remain diagnostic`() {
        val source = runtimeSource()
        val metricsBody = functionBody(source, "metrics")
        val imageBody = functionBody(source, "imageReady")
        val stableBody = functionBody(source, "notifyStable")
        val readerFontBody = functionBody(source, "readerFontReady")
        val initialStableBody = functionBody(source, "publishInitialStable")
        val flushLayoutBody = functionBody(source, "flushLayoutRefresh")
        val layoutPendingBody = functionBody(source, "isLayoutPending")

        assertTrue("resourcesReady:resourcesReady" in metricsBody)
        assertTrue("resourcesFailed:resourcesFailed" in metricsBody)
        assertTrue("layoutRevision:layoutRevision" in metricsBody)
        assertTrue("layoutPending:isLayoutPending()" in metricsBody)
        assertTrue("layoutRefreshRequested||layoutRefreshFrame||layoutRefreshTimer||" in layoutPendingBody)
        assertTrue("layoutRefreshInProgress||layoutRefreshDeferredBySelection" in layoutPendingBody)
        assertFalse("sourceImagesPending" in layoutPendingBody)
        assertTrue("activationTargetRevision:activationTargetRevision" in metricsBody)
        assertTrue("activationTargetSatisfied:" in metricsBody)
        assertTrue("decodeImage(img)" in imageBody)
        assertTrue("stable=true" in stableBody)
        assertTrue("flushLayoutRefresh(function()" in stableBody)
        assertFalse("markLayoutDirty()" in stableBody)
        assertTrue(
            "return Promise.all([fontsReady(),Promise.all(images)," +
                "Promise.all(svgImages),backgroundImagesReady()])" in source
        )
        assertTrue("function stylesheetReady(link)" in source)
        assertTrue("function markResourceFailure()" in source)
        assertTrue("typeof __LEGADO_READER_FONT__==='undefined'?false" in source)
        assertTrue("document.fonts.load(\"1em 'legado-reader-font'\")" in readerFontBody)
        assertTrue("if(!readerFontConfigured" in readerFontBody)
        assertTrue("if(!runtimeActive||initialStablePublished)return" in initialStableBody)
        assertTrue(
            initialStableBody.indexOf("if(!runtimeActive||initialStablePublished)return") <
                initialStableBody.indexOf("initialStablePublished=true")
        )
        assertTrue("flushLayoutRefresh(notifyStable)" in initialStableBody)
        assertTrue("runLayoutRefresh()" in flushLayoutBody)
        assertFalse("publishAfterLayout" in source)
        assertTrue("resourcesReady=true" in initialStableBody)
        assertTrue("Promise.race([allResourcesReady,resourceSettlementTimeout])" in source)
        assertTrue("Promise.all([" in source)
        assertTrue("readerFontReady()," in source)
        assertTrue("setTimeout(function(){markResourceFailure();resolve();},3200)" in source)
        assertFalse("resourcesReadyBeforeFinalSettlement" in source)
        assertFalse("if(stable){scheduleLayoutRefresh(true);notifyStable();}" in source)
        assertFalse("setTimeout(resolve,3000)" in source)
    }

    @Test
    fun `runtime publishes one replayable terminal result per token`() {
        val source = runtimeSource()
        val bootstrap = source.substringBefore("var activeToken=")
        val replayBody = functionBody(source, "replayRuntimeTerminal")
        val settleBody = functionBody(source, "settleRuntime")
        val stableBody = functionBody(source, "notifyStable")

        assertTrue("window.__legadoEpub.setToken(TOKEN)" in bootstrap)
        assertTrue("api.replayRuntimeTerminal(TOKEN)" in bootstrap)
        assertTrue("api.metrics().ready" in bootstrap)
        assertTrue("if(runtimeTerminal)return" in settleBody)
        assertTrue("runtimeTerminalReportedToken===nextToken" in replayBody)
        assertTrue("runtimeTerminalReportedToken=nextToken" in replayBody)
        assertTrue("bridge.onStable(nextToken)" in replayBody)
        assertTrue("bridge.onFontError(nextToken,runtimeTerminal.message)" in replayBody)
        assertTrue("stable=true" in stableBody)
        assertTrue("settleRuntime('ready','')" in stableBody)
        assertTrue("settleRuntime('reader-font-error'" in source)
    }

    @Test
    fun `native runtime watchdog only owns a pending terminal token`() {
        val source = webLayerSource()
        val terminalBody = kotlinFunctionBody(source, "onRuntimeTerminal")
        val consumeBody = kotlinFunctionBody(source, "consumeRuntimeTerminal")
        val installBody = kotlinFunctionBody(source, "installRuntime")
        val timeoutBody = kotlinFunctionBody(source, "scheduleRuntimeStableTimeout")
        val promoteBody = kotlinFunctionBody(source, "promote")

        assertTrue("recordRuntimeTerminal(token, kind, message)" in terminalBody)
        assertTrue("view.cancelRuntimeStableTimeout()" in terminalBody)
        assertTrue("if (view.runtimeInstalled) consumeRuntimeTerminal(view, token)" in terminalBody)
        assertTrue("RuntimeTerminalKind.ReaderFontError" in consumeBody)
        assertTrue("discardFailedPreload(view)" in consumeBody)
        assertTrue("failPendingLoad(view, token, detail)" in consumeBody)
        assertTrue("if (isRuntimeTerminalPending(installToken))" in installBody)
        assertTrue("consumeRuntimeTerminal(this@ReaderWebView, installToken)" in installBody)
        assertTrue("!isRuntimeTerminalPending(expectedToken)" in timeoutBody)
        assertTrue("resetRuntimeTerminal(token)" in promoteBody)
    }

    @Test
    fun `font document runtime and activation stages all have hard deadlines`() {
        val runtime = runtimeSource()
        val webLayer = webLayerSource()
        val activity = readBookActivitySource()
        val readerFontBody = functionBody(runtime, "readerFontReady")
        val documentTimeoutBody = kotlinFunctionBody(webLayer, "scheduleDocumentLoadTimeout")
        val runtimeTimeoutBody = kotlinFunctionBody(webLayer, "scheduleRuntimeStableTimeout")
        val activationTimeoutBody = kotlinFunctionBody(webLayer, "scheduleActivationTimeout")
        val activityTimeoutBody = kotlinFunctionBody(activity, "scheduleEpubCoreLoadTimeout")

        assertTrue("Custom EPUB reader font timed out" in readerFontBody)
        assertTrue("},3000)" in readerFontBody)
        assertTrue("postDelayed(timeout, DOCUMENT_LOAD_TIMEOUT_MS)" in documentTimeoutBody)
        assertTrue("postDelayed(timeout, if (preparedChapter?.readerTemplate != null) TEMPLATE_STABLE_TIMEOUT_MS else RUNTIME_STABLE_TIMEOUT_MS)" in runtimeTimeoutBody)
        assertTrue("postDelayed(timeout, timeoutMillis)" in activationTimeoutBody)
        assertTrue("readerTemplateActiveClock.now() + 60_000L" in activityTimeoutBody)
        assertTrue("delay(EPUB_PREPARE_TIMEOUT_MS)" in activityTimeoutBody)
        assertTrue("private const val DOCUMENT_LOAD_TIMEOUT_MS = 12_000L" in webLayer)
        assertTrue("private const val RUNTIME_STABLE_TIMEOUT_MS = 8_000L" in webLayer)
        assertTrue("private const val TEMPLATE_STABLE_TIMEOUT_MS = 45_000L" in webLayer)
        assertTrue("private const val PAGE_ACTIVATION_TIMEOUT_MS = 8_000L" in webLayer)
        assertTrue("private const val EPUB_PREPARE_TIMEOUT_MS = 15_000L" in activity)
        assertFalse("MAX_READY_ATTEMPTS" in webLayer)
        assertFalse("publishAfterLayout" in runtime)
    }

    @Test
    fun `custom reader font overrides publisher css and participates in runtime settlement`() {
        val activitySource = readBookActivitySource()
        val readerCssBody = kotlinFunctionBody(documentBuilderSource(), "readerCss")
        val publisherStyledCss = readerCssBody
            .substringAfter("if (layoutMode == EpubDirectLayoutMode.PUBLISHER_STYLED)")
            .substringBefore("if (layoutMode == EpubDirectLayoutMode.INTERACTIVE")
        val runtimeScriptBody = kotlinFunctionBody(webLayerSource(), "runtimeScript")

        assertTrue("readerFontOverridePublisher = font != null" in activitySource)
        assertTrue("readerFontRevision = font?.revision" in activitySource)
        assertTrue("readerFontLength = font?.length" in activitySource)
        assertTrue("\$fontFace" in publisherStyledCss)
        assertTrue("font-display:block" in readerCssBody)
        assertTrue("\${readerFontRules.publisherOverride}" in publisherStyledCss)
        assertTrue("\${readerFontRules.inherited}" in publisherStyledCss)
        assertTrue("\"__LEGADO_READER_FONT__\" to (" in runtimeScriptBody)
        assertTrue("!config.readerFontUrl.isNullOrBlank()" in runtimeScriptBody)
        assertTrue("!config.readerFontPath.isNullOrBlank()" in runtimeScriptBody)
        assertTrue("!config.readerFontRevision.isNullOrBlank()" in runtimeScriptBody)
    }

    @Test
    fun `direct typography uses the same measured metrics as the text reader`() {
        val body = kotlinFunctionBody(readBookActivitySource(), "buildEpubCoreLayoutConfig")
        val runtime = runtimeSource()
        val builder = documentBuilderSource()

        assertTrue("val textHeightPx = textPaint.textHeight" in body)
        assertTrue("StaticLayout.getDesiredWidth(it, textPaint)" in body)
        assertTrue("paragraphSpacingPx = textHeightPx * ReadBookConfig.paragraphSpacing / 10f" in body)
        assertTrue("lineHeightPx = textHeightPx * ReadBookConfig.lineSpacingExtra / 10f" in body)
        assertTrue("textFontWeight = ReadBookConfig.textWeight" in body)
        assertTrue("AppConfig.paddingDisplayCutouts" in body)
        assertTrue("WindowInsetsCompat.Type.displayCutout()" in body)
        assertTrue("readerSafeInsetLeftPx = displayCutout?.left ?: 0" in body)
        assertTrue("p[data-legado-reader-paragraph]" in builder)
        assertTrue("line-height:var(--legado-line-grid,var(--legado-line-height-base))!important" in builder)
        assertTrue("function markReaderParagraphs()" in runtime)
        assertTrue("function alignReaderLineGrid()" in runtime)
        assertFalse("blockquote,li,h1,h2,h3,h4,h5,h6" in runtime)
        assertTrue("paragraph.setAttribute('data-legado-reader-paragraph','true')" in runtime)
        assertTrue("?!fixed&&!publisherStyled:__LEGADO_READER_TYPOGRAPHY__" in runtime)
        assertFalse("coerceAtLeast(8)" in body)
        assertFalse("paragraphIndent.length" in body)
    }

    @Test
    fun `direct epub hides text-only menu and advanced chrome entry points`() {
        val activity = readBookActivitySource()
        val tipConfig = tipConfigDialogSource()
        assertTrue("R.id.menu_edit_content" in activity)
        assertTrue("item.isVisible = !isEpubCoreMode()" in activity)
        assertTrue("val titleModeOptions = if (directEpub)" in tipConfig)
        assertTrue("EpubReaderChromeModePolicy.selectableModes" in tipConfig)
        assertTrue("manageLabel = if (!directEpub" in tipConfig)
        assertTrue("if (directEpub) return if (index == 2) 2 else index" in tipConfig)
    }

    @Test
    fun `theme selection refreshes animation and only the guarded EPUB background surface`() {
        val activity = readBookActivitySource()
        val dialog = readStyleDialogSource()
        val webLayer = webLayerSource()
        val changeThemeBody = kotlinFunctionBody(dialog, "changeBgTextConfig")
        val pageAnimBody = kotlinFunctionBody(activity, "upPageAnim")
        val configUpdateBody = kotlinFunctionBody(activity, "handleEpubCoreConfigUpdate")
        val hostStyleBody = kotlinFunctionBody(activity, "upEpubRendererStyle")
        val directBackgroundBody = kotlinFunctionBody(webLayer, "refreshReaderSurfaceBackground")

        assertTrue("ReadBook.book?.setPageAnim(-1)" in changeThemeBody)
        assertTrue("onThemeApplied = { selectedAnim = ReadBook.pageAnim() }" in dialog)
        assertTrue("refreshDirectPageAnimationStyle()" in pageAnimBody)
        assertTrue("refreshEpubReaderBackgroundImmediately()" in configUpdateBody)
        assertTrue("binding.epubReadView.background = null" in hostStyleBody)
        assertFalse("binding.epubReadView.background = ReadBookConfig.bg" in hostStyleBody)
        assertTrue("EpubReaderBackgroundPolicy.shouldUseReaderBackground" in directBackgroundBody)
    }

    @Test
    fun `page animation callback always crosses onto the activity main thread`() {
        val body = kotlinFunctionBody(readBookActivitySource(), "upPageAnim")

        val mainThread = body.indexOf("runOnUiThread")
        assertTrue(mainThread >= 0)
        assertTrue(body.indexOf("binding.readView.upPageAnim(upRecorder)") > mainThread)
        assertTrue(body.indexOf("binding.epubReadView.refreshDirectPageAnimationStyle()") > mainThread)
    }

    @Test
    fun `broken image boxes do not qualify as rendered page pixels`() {
        val source = runtimeSource()
        val imageBody = functionBody(source, "imageHasPixels")
        val visualBody = functionBody(source, "visualHasPixels")
        val renderableBody = functionBody(source, "computeRenderableContent")
        val viewportBody = functionBody(source, "computeViewportRenderable")
        val backgroundBody = functionBody(source, "backgroundHasPixels")
        val svgImageBody = functionBody(source, "svgImageHasPixels")
        val svgBody = functionBody(source, "svgHasPixels")

        assertTrue("image.complete" in imageBody)
        assertTrue("Number(image.naturalWidth)>0" in imageBody)
        assertTrue("Number(image.naturalHeight)>0" in imageBody)
        assertTrue("imageHasPixels(element)" in visualBody)
        assertTrue("visualHasPixels(visuals[j])" in renderableBody)
        assertTrue("visualHasPixels(visuals[j])" in viewportBody)
        assertTrue("loadedBackgroundImages[urls[i]]===true" in backgroundBody)
        assertTrue("loadedSvgImages[url]===true" in svgImageBody)
        assertTrue("svgImageHasPixels(images[i])" in svgBody)
        assertTrue("querySelectorAll('svg image')" in source)
        assertTrue("if(url&&!svgImageSeen[url])" in source)
    }

    @Test
    fun `direct documents use the tolerant html parser used by the reference engine`() {
        assertEquals("text/html", EpubDirectWebLayer.DOCUMENT_MIME_TYPE)
    }

    @Test
    fun `content page handoff reuses the exact committed snapshot`() {
        val source = webLayerSource()
        val body = kotlinFunctionBody(source, "preservePageTransitionSnapshot")

        assertTrue("takeCommittedPageSnapshot(" in body)
        assertFalse("captureView(" in body)
    }

    @Test
    fun `same chapter turn combines the committed source with the isolated adjacent target`() {
        val source = webLayerSource()
        val animationBody = kotlinFunctionBody(source, "startPageAnimation")
        val applyBody = kotlinFunctionBody(source, "applyPage")
        val commitBody = kotlinFunctionBody(source, "commitPageAndReaderChrome")
        val verificationBody = kotlinFunctionBody(source, "verifyAnimationTargetPage")
        val visualStateBody = kotlinFunctionBody(source, "completePageAnimationVisualState")

        assertTrue("val source = takeCommittedPageSnapshot(" in animationBody)
        assertFalse("val source = captureView(" in animationBody)
        assertFalse("captureView(" in animationBody)
        assertTrue("val targetBitmap = takeAdjacentPageBitmap(" in animationBody)
        assertTrue("targetBitmap = targetBitmap" in animationBody)
        assertTrue("opaqueBackground = true" in animationBody)
        assertTrue("bindLivePageAnimationTarget(" in animationBody)
        assertTrue("applyPage(view, index, animate = false)" in animationBody)
        assertTrue("verifyAnimationTargetPage(" in animationBody)
        assertTrue("revealLivePageAnimationTarget(overlay)" in animationBody)
        assertTrue("restoreSource(\"target-timeout\"" in animationBody)
        assertTrue("commitPageAndReaderChrome(" in applyBody)
        assertTrue("api.metrics()" in commitBody)
        assertTrue("view.evaluateJavascript(MEASURE_VIEWPORT_SCRIPT)" in verificationBody)
        assertTrue("EpubDirectActivationTargetPolicy.isSatisfied(" in verificationBody)
        assertStableRenderStateVerification(source, verificationBody)
        assertFalse("metrics.resourcesReady" in verificationBody)
        assertFalse("completeAfterCompositorFrames(view)" in verificationBody)
        assertTrue("postVisualStateCallback" in visualStateBody)
        assertTrue("PAGE_ANIMATION_VISUAL_STATE_TIMEOUT_MS" in visualStateBody)
    }

    @Test
    fun `reader chrome updates cannot cancel an in flight page commit`() {
        val source = webLayerSource()
        val chromeBody = kotlinFunctionBody(source, "applyReaderChromeToView")
        val commitBody = kotlinFunctionBody(source, "commitPageAndReaderChrome")

        assertTrue("view.appliedReaderChromeConfig == payload.config" in chromeBody)
        assertTrue("view.appliedReaderChromeData == payload.data" in chromeBody)
        assertTrue("val commitSequence = ++view.pageCommitSequence" in commitBody)
        assertTrue("if (view.pageCommitSequence != commitSequence)" in commitBody)
        assertFalse(
            "reader chrome sequence must not cancel the page callback",
            "if (view.readerChromeApplySequence != chromeApplySequence) return" in commitBody
        )
    }

    @Test
    fun `reader chrome completion resumes committed snapshot capture`() {
        val source = webLayerSource()
        val chromeApplyBody = kotlinFunctionBody(source, "applyReaderChromePayloadToView")
        val commitBody = kotlinFunctionBody(source, "commitPageAndReaderChrome")
        val resumeBody = kotlinFunctionBody(source, "resumeCommittedSnapshotAfterReaderChrome")
        val blockerBody = kotlinFunctionBody(source, "hasCommittedSnapshotCaptureBlocker")

        assertTrue("view.readerChromeApplyInFlight" in blockerBody)
        assertTrue("resumeCommittedSnapshotAfterReaderChrome(" in chromeApplyBody)
        assertTrue("resumeCommittedSnapshotAfterReaderChrome(" in commitBody)
        assertTrue("scheduleCommittedPageSnapshotRefresh(reason)" in resumeBody)
    }

    @Test
    fun `animation style refresh preserves the committed page transaction and snapshot`() {
        val body = kotlinFunctionBody(webLayerSource(), "refreshPageAnimationStyle")

        assertTrue("scheduleCommittedPageSnapshotRefresh(\"page-animation-style-changed\")" in body)
        assertTrue("syncAdjacentPageFrames()" in body)
        assertFalse("invalidateCommittedPageSnapshot()" in body)
        assertFalse("applyPage(" in body)
        assertFalse("cancelPageHandoff()" in body)
        assertFalse("cancelPendingActivation()" in body)
        assertFalse("cancelPendingChapterTurn()" in body)
        assertFalse("cancelPageAnimation(" in body)
    }

    @Test
    fun `latest chrome owner releases snapshot blocker before stale page guards`() {
        val body = kotlinFunctionBody(webLayerSource(), "commitPageAndReaderChrome")
        val release = body.indexOf("if (ownsChromeApply) view.readerChromeApplyInFlight = false")
        val tokenGuard = body.indexOf("if (view.token != token || view.loadedChapterKey != chapterKey)")
        val pageGuard = body.indexOf("if (view.pageCommitSequence != commitSequence)")

        assertTrue(release >= 0)
        assertTrue(release < tokenGuard)
        assertTrue(release < pageGuard)
        assertTrue("superseded-page-and-reader-chrome" in body)
    }

    @Test
    fun `blocked snapshot capture retries a finite number of frames`() {
        val body = kotlinFunctionBody(webLayerSource(), "scheduleCommittedPageSnapshotRefresh")

        assertTrue("blockedAttempt" in body)
        assertTrue("MAX_BLOCKED_SNAPSHOT_RETRIES" in body)
        assertTrue("postDelayed(retry, SNAPSHOT_CAPTURE_RETRY_MS)" in body)
        assertTrue("committedSnapshotRefreshRunnable != null" in body)
    }

    @Test
    fun `same chapter animation commits the requested native page`() {
        val body = kotlinFunctionBody(webLayerSource(), "setPage")

        assertTrue("maxOf(metrics?.pageCount ?: pageCount, target + 1, 1)" in body)
        assertTrue("val nextIndex = target.coerceIn(0, nextCount - 1)" in body)
        assertFalse("metrics?.pageIndex?.coerceIn" in body)
    }

    @Test
    fun `horizontal drag drives the snapshot overlay before touch release`() {
        val source = webLayerSource()
        val beginBody = kotlinFunctionBody(source, "beginInteractivePageTurn")
        val updateBody = kotlinFunctionBody(source, "updateInteractivePageTurn")
        val touchBody = kotlinFunctionBody(source, "onTouchEvent")
        val restoreBody = kotlinFunctionBody(source, "restoreInteractiveSource")
        val maybeSettleBody = kotlinFunctionBody(source, "maybeStartInteractiveSettle")
        val settleBody = kotlinFunctionBody(source, "startInteractiveSettle")
        val completeSettleBody = kotlinFunctionBody(source, "completeInteractiveSettle")
        val verifyTargetBody = kotlinFunctionBody(source, "verifyAnimationTargetPage")

        assertTrue("val sourceBitmap = takeCommittedPageSnapshot(" in beginBody)
        assertFalse("val sourceBitmap = captureView(" in beginBody)
        assertFalse("captureView(" in beginBody)
        assertFalse("hasAdjacentPageFrame(" in beginBody)
        assertTrue("val targetBitmap = takeAdjacentPageBitmap(" in beginBody)
        assertFalse("preserveBitmapAsCommittedSnapshot(" in beginBody)
        assertFalse("targetVisualReady" in beginBody)
        assertTrue("suspendAdjacentPageFrameScheduling()" in beginBody)
        assertTrue("mountPageAnimationOverlay(" in beginBody)
        assertTrue("bindLivePageAnimationTarget(" in beginBody)
        assertFalse("chapter?.layoutMode?.singlePage == true" in beginBody)
        assertTrue("applyPage(view, targetPageIndex, animate = false)" in beginBody)
        assertTrue(
            beginBody.indexOf("if (!hasAdjacentPageFrame(") <
                beginBody.indexOf("val sourceBitmap = takeCommittedPageSnapshot(")
        )
        assertTrue(
            beginBody.indexOf("mountPageAnimationOverlay(") <
                beginBody.indexOf("applyPage(view, targetPageIndex, animate = false)")
        )
        assertTrue("turn.gesture.update(deltaX)" in updateBody)
        assertTrue("turn.gesture.progress(" in updateBody)
        assertTrue("turn.requestedProgress =" in updateBody)
        assertTrue("setPageAnimationProgress(turn.overlay, turn.requestedProgress)" in updateBody)
        assertFalse("targetVisualReady" in updateBody)
        assertTrue("updateInteractivePageTurn(this, dx, touchYFraction)" in touchBody)
        assertTrue("beginInteractivePageTurn(this, dx, pageTouchSlop)" in touchBody)
        assertTrue(
            touchBody.indexOf("beginInteractivePageTurn(this, dx, pageTouchSlop)") !=
                touchBody.lastIndexOf("beginInteractivePageTurn(this, dx, pageTouchSlop)")
        )
        assertFalse("shouldCompleteInteractiveTurn(" in touchBody)
        assertTrue("activeTurn.gesture.shouldCommit(" in touchBody)
        assertTrue("finishInteractivePageTurn(this, commit, velocityX)" in touchBody)
        assertTrue("downX = event.rawX" in touchBody)
        assertTrue("val dx = event.rawX - downX" in touchBody)
        assertTrue("pageTouchSlop" in touchBody)
        assertTrue("scheduleCommittedPageSnapshotRefresh(\"gesture-down\")" in touchBody)
        assertTrue("addRawVelocityMovement(event)" in touchBody)
        assertFalse("!turn.targetApplied" in maybeSettleBody)
        assertTrue("interpolator = LINEAR_INTERPOLATOR" in settleBody)
        assertTrue("EpubDirectPageAnimationPolicy.settleMotion(" in settleBody)
        assertTrue("settleMotion.progress(" in settleBody)
        assertFalse("pageAnimationInterpolator(turn.style)" in settleBody)
        assertTrue("EpubDirectActivationTargetPolicy.isSatisfied(" in verifyTargetBody)
        assertStableRenderStateVerification(source, verifyTargetBody)
        assertFalse("metrics.resourcesReady" in verifyTargetBody)
        assertTrue("embeddedInteractionAtDown = lastEmbeddedInteractionAt" in touchBody)
        assertTrue("lastEmbeddedInteractionAt == embeddedInteractionAtDown" in touchBody)
        assertTrue("!nativeHitConsumesReaderTap()" in touchBody)
        assertTrue("pendingTap?.let" in touchBody)
        assertFalse("TAP_DEFERRAL_MS" in source)
        assertTrue("a[href]" in functionBody(runtimeSource(), "interactiveTarget"))
        val nativeHitBody = kotlinFunctionBody(source, "nativeHitConsumesReaderTap")
        assertTrue("HitTestResult.SRC_ANCHOR_TYPE" in nativeHitBody)
        assertTrue("HitTestResult.SRC_IMAGE_ANCHOR_TYPE" in nativeHitBody)
        assertTrue("val targetReady = turn.targetApplied" in completeSettleBody)
        assertTrue("setPageAnimationProgress(turn.overlay, 0f)" in restoreBody)
        assertTrue("stopInteractiveSettle(turn)" in restoreBody)
        assertTrue("applyPage(turn.sourceView, turn.sourcePageIndex, animate = false)" in restoreBody)
    }

    @Test
    fun `chapter boundary drag keeps one overlay across asynchronous activation`() {
        val source = webLayerSource()
        val beginBody = kotlinFunctionBody(source, "beginInteractiveChapterTurn")
        val previewBody = kotlinFunctionBody(source, "prepareInteractiveBoundaryPreview")
        val finishBody = kotlinFunctionBody(source, "finishInteractivePageTurn")
        val showBody = kotlinFunctionBody(source, "showChapterInternal")
        val activationBody = kotlinFunctionBody(source, "completeActivation")
        val completeBody = kotlinFunctionBody(source, "completeInteractiveChapterActivation")
        val settleBody = kotlinFunctionBody(source, "completeInteractiveSettle")
        val updateBody = kotlinFunctionBody(source, "updateInteractivePageTurn")
        val activeBody = kotlinFunctionBody(source, "isInteractivePageTurnActive")
        val verifyBody = kotlinFunctionBody(source, "verifyAnimationTargetPage")
        val candidateBody = kotlinFunctionBody(source, "applyCandidatePage")
        val candidateVerificationBody = kotlinFunctionBody(source, "isCandidateActivationVerified")

        assertFalse("hasAdjacentPageFrame(" in beginBody)
        assertTrue("prepareChapterTurn(" in beginBody)
        assertTrue("scheduleTimeout = false" in beginBody)
        assertTrue("opaqueBackground = true" in beginBody)
        assertTrue("val targetBitmap = pending.targetBitmap" in beginBody)
        assertFalse("targetVisualReady" in beginBody)
        assertTrue("suspendAdjacentPageFrameScheduling()" in beginBody)
        assertTrue("prepareInteractiveBoundaryPreview(turn)" in beginBody)
        assertTrue("EpubDirectActivationTargetPolicy.chapterBoundary(" in previewBody)
        assertTrue("openAtEnd = turn.logicalDirection < 0" in previewBody)
        assertTrue("applySemanticBoundaryPage(" in previewBody)
        assertFalse("preparedPageCount.coerceAtLeast(1) - 1" in previewBody)
        assertTrue("target = activationTarget" in previewBody)
        assertTrue("turn.requestedProgress" in previewBody)
        assertFalse("requireResourcesReady" in previewBody + verifyBody)
        assertTrue("turn.finishRequested == null && !turn.settling" in previewBody)
        assertTrue("EpubDirectActivationTargetPolicy.isSatisfied(" in verifyBody)
        assertFalse("metrics.resourcesReady" in verifyBody)
        assertStableRenderStateVerification(source, verifyBody)
        assertTrue("if (metrics == null || metrics.layoutPending) return false" in candidateVerificationBody)
        assertTrue("EpubDirectActivationTargetPolicy.isSatisfied(" in candidateVerificationBody)
        assertFalse("metrics.resourcesReady" in candidateBody + candidateVerificationBody)
        assertTrue("dispatchBoundary(turn.logicalDirection, accepted = true)" in finishBody)
        assertTrue("startInteractiveSettle(turn, commit = true)" in finishBody)
        assertTrue("if (turn.finishRequested != null || turn.settling || turn.restoring) return false" in updateBody)
        assertTrue("val interactiveBoundaryTurn = chapterTurn?.let(::interactiveTurnFor)" in showBody)
        assertTrue("if (interactiveBoundaryTurn == null)" in showBody)
        val invalidTurnCancel = showBody.indexOf("cancelPendingChapterTurn()")
        val generationAdvance = showBody.indexOf("generation++")
        val staleQueueReset = showBody.indexOf("clearQueuedPageTurns()")
        assertTrue(invalidTurnCancel >= 0)
        assertTrue(invalidTurnCancel < generationAdvance)
        assertTrue(staleQueueReset > generationAdvance)
        assertTrue("interactiveBoundaryTurn?.targetToken = token" in showBody)
        assertTrue("completeInteractiveChapterActivation(" in activationBody)
        assertTrue("revealLivePageAnimationTarget(turn.overlay)" in completeBody)
        assertTrue("maybeStartInteractiveSettle(turn)" in completeBody)
        assertTrue("if (turn.boundary && turn.overlay.progress < 0.999f)" in settleBody)
        assertTrue("startInteractiveSettle(turn, commit = true)" in settleBody)
        assertTrue("turn.navigationDispatched && turn.targetToken == generation" in activeBody)
    }

    @Test
    fun `boundary activation retries one semantic page before the deadline restores the committed reader`() {
        val source = webLayerSource()
        val readyBody = kotlinFunctionBody(source, "onDocumentReady")
        val probeBody = kotlinFunctionBody(source, "probePendingActivation")
        val candidateBody = kotlinFunctionBody(source, "applyCandidatePage")
        val timeoutBody = kotlinFunctionBody(source, "scheduleActivationTimeout")
        val failureBody = kotlinFunctionBody(source, "failPendingLoad")
        val setupFailureBody = kotlinFunctionBody(source, "failCandidateSetup")
        val restoreBody = kotlinFunctionBody(source, "restoreCommittedViewAfterCandidateFailure")
        val commitBody = kotlinFunctionBody(source, "commitPageAndReaderChrome")

        assertTrue("requestPendingActivationProbe(view = view, token = token, immediate = true)" in readyBody)
        assertTrue("view.evaluateJavascript(MEASURE_SCRIPT)" in probeBody)
        assertTrue("applyCandidatePage(" in probeBody)
        assertTrue("requestPendingActivationProbe" in source)
        assertTrue("commitPageAndReaderChrome(" in candidateBody)
        assertTrue("api.setActivationPage" in commitBody)
        assertTrue("completePageAnimationVisualState" in candidateBody)
        assertTrue("isCandidateActivationVerified(" in candidateBody)
        assertTrue("requestPendingActivationProbe(view, token)" in candidateBody)
        assertFalse("failPendingLoad(" in candidateBody)
        assertFalse("activationDeadlineAt" in candidateBody)
        assertTrue("PAGE_ACTIVATION_TIMEOUT_MS" in timeoutBody)
        assertFalse("EpubDirectActivationTimeoutPolicy" in timeoutBody)
        assertTrue("clearQueuedPageTurns()" in failureBody)
        assertTrue("releasePendingChapterTurn(resumeAdjacentFrames = false)" in failureBody)
        assertTrue("cancelPageAnimation()" in failureBody)
        assertTrue("standbyWebView = null" in failureBody)
        assertTrue("destroyWebView(view)" in failureBody)
        assertTrue("restoreCommittedViewAfterCandidateFailure(committedView)" in failureBody)
        assertTrue("clearQueuedPageTurns()" in setupFailureBody)
        assertTrue("releasePendingChapterTurn(resumeAdjacentFrames = false)" in setupFailureBody)
        assertTrue("cancelPageAnimation()" in setupFailureBody)
        assertTrue("restoreCommittedViewAfterCandidateFailure(committedView)" in setupFailureBody)
        assertTrue("pendingPageIndex = pageIndex" in restoreBody)
        assertTrue("committedView.bringToFront()" in restoreBody)
    }

    @Test
    fun `unverified boundary target remains opaque until a live target is ready`() {
        val source = pageOverlaySource()
        val drawBody = kotlinFunctionBody(source, "onDraw")

        assertTrue("if (opaqueBackground || drawableTargetBitmap() != null)" in drawBody)
        assertTrue("fun revealLiveTarget()" in source)
        assertTrue("private val targetBitmap: Bitmap?" in source)
        assertTrue("fun takeTargetBitmap(): Bitmap?" in source)
        assertFalse("fun setTargetBitmap" in source)
    }

    @Test
    fun `animation timeout restores the source before removing the overlay`() {
        val body = kotlinFunctionBody(webLayerSource(), "startPageAnimation")

        assertTrue("restoreSource(\"target-timeout\"" in body)
        assertTrue("applyPage(view, sourcePageIndex, animate = false)" in body)
        assertTrue("finishRestore(verified = true)" in body)
        assertTrue("cancelPageAnimation()" in body)
        assertFalse("startAfterCommit(null)" in body)
    }

    @Test
    fun `unavailable source snapshot degrades to the verified page handoff`() {
        val source = webLayerSource()
        val setPageBody = kotlinFunctionBody(source, "setPage")
        val applyBody = kotlinFunctionBody(source, "applyPage")
        val commitBody = kotlinFunctionBody(source, "commitPageAndReaderChrome")

        assertTrue("requestedStyle != EpubDirectPageAnimationPolicy.Style.None" in setPageBody)
        assertTrue("scheduleCommittedPageSnapshotRefresh(" in setPageBody)
        assertTrue("pageHandoffRequest = request" in setPageBody)
        assertTrue("verifyPageHandoff(" in setPageBody)
        assertTrue(
            setPageBody.indexOf("scheduleCommittedPageSnapshotRefresh(") <
                setPageBody.indexOf("pageHandoffRequest = request")
        )
        assertFalse("liveFallbackDurationMillis" in setPageBody)
        assertFalse("horizontalAnimationMillis" in setPageBody)
        assertTrue("commitPageAndReaderChrome(" in applyBody)
        assertTrue("api.setReaderChrome(" in commitBody)
        assertTrue("api.setReaderChromeData(" in commitBody)
        assertTrue("api.setPage(\$safePageIndex,0,'\$behavior')" in commitBody)
        assertTrue(
            commitBody.indexOf("api.setReaderChromeData(") <
                commitBody.indexOf("pageCommand +")
        )
    }

    @Test
    fun `animated horizontal runtime reports only after target scroll settles`() {
        val source = runtimeSource()
        val horizontalBody = functionBody(source, "setHorizontalScrollPage")
        val setPageBody = functionBody(source, "setPage")
        val commitAnchorBody = functionBody(source, "commitViewportAnchor")

        assertTrue("commitViewportAnchor();report()" in horizontalBody)
        assertTrue("committedViewportAnchor=captureViewportAnchor()" in commitAnchorBody)
        assertTrue("var animating=setHorizontalScrollPage(animationMs)" in setPageBody)
        assertTrue("if(animating)return" in setPageBody)
    }

    @Test
    fun `prepared neighbour preload starts on the next display frame`() {
        val source = webLayerSource()
        val body = kotlinFunctionBody(source, "preloadChapter")
        val schedule = kotlinFunctionBody(source, "resumeScheduledPreloads")

        assertTrue("resumeScheduledPreloads()" in body)
        assertTrue("postOnAnimation(next.action)" in schedule)
        assertTrue("if (!canStartPreload()) return" in schedule)
        assertTrue("!preloadGestureActive && loadingPreloadedWebViews.isEmpty()" in source)
        assertFalse("postDelayed(preload" in body)
        assertFalse("postDelayed(" in schedule)
    }

    @Test
    fun `preload cache waits for stable visual state and confirmed metrics`() {
        val body = kotlinFunctionBody(webLayerSource(), "completePreload")

        assertTrue("preloadVerificationInFlight" in body)
        assertTrue("stable = view.runtimeStable" in body)
        assertFalse("metrics.layoutPending" in body)
        assertFalse("metrics.resourcesReady" in body)
        assertTrue("completeAfterVisualState(view)" in body)
        assertTrue("val confirmedMetrics = parseMetrics(confirmedRaw)" in body)
        assertTrue("val stableMetrics = checkNotNull(confirmedMetrics)" in body)
        assertTrue("view.preparedPageCount = metrics.pageCount" in body)
        assertTrue("applyReaderChromePayloadToView(view, payload)" in body)
        assertTrue("readerChromePayloadMatches(view, payload)" in body)
        assertTrue(
            body.indexOf("applyReaderChromePayloadToView(view, payload)") <
                body.indexOf("view.preloadReady = true")
        )
        assertTrue("completeAfterVisualState(view) {" in body)
        assertTrue("parseMetrics(confirmedRaw)" in body)
    }

    @Test
    fun `promoted runtime replays stable for its new generation`() {
        val runtime = runtimeSource()
        val bootstrap = runtime.substringBefore("var activeToken=")
        val terminalBody = kotlinFunctionBody(webLayerSource(), "consumeRuntimeTerminal")
        val installBody = kotlinFunctionBody(webLayerSource(), "installRuntime")

        assertTrue("window.__legadoEpub.setToken(TOKEN)" in bootstrap)
        assertTrue("requestAnimationFrame(function(){" in bootstrap)
        assertTrue("api.token===TOKEN" in bootstrap)
        assertTrue("api.replayRuntimeTerminal(TOKEN)" in bootstrap)
        assertTrue("bridge.onStable(TOKEN)" in bootstrap)
        assertTrue("else if (token != generation)" in terminalBody)
        assertTrue("runtimeScript(installToken, chapter, config)" in installBody)
        assertTrue("scheduleRuntimeStableTimeout()" in installBody)
        assertFalse("stableAlready" in installBody)
    }

    @Test
    fun `chapter boundary reuses the reference snapshot overlay after visual verification`() {
        val source = webLayerSource()
        val prepareBody = kotlinFunctionBody(source, "prepareChapterTurn")
        val candidateBody = kotlinFunctionBody(source, "applyCandidatePage")
        val activationBody = kotlinFunctionBody(source, "completeActivation")
        val turnBody = kotlinFunctionBody(source, "completeChapterTurn")
        val interactiveBody = kotlinFunctionBody(source, "completeInteractiveChapterActivation")
        val revealBody = kotlinFunctionBody(source, "revealActivatedWebViewWithoutAnimation")

        assertTrue("val sourceBitmap = if (style != EpubDirectPageAnimationPolicy.Style.None)" in prepareBody)
        assertTrue("takeCommittedPageSnapshot(" in prepareBody)
        assertFalse("sourceBitmap = captureView(" in prepareBody)
        assertTrue("val targetBitmap = sourceBitmap?.let" in prepareBody)
        assertTrue("takeAdjacentPageBitmap(" in prepareBody)
        assertTrue("expectedPageIndex = if (normalizedDirection > 0) 0 else null" in prepareBody)
        assertTrue("pendingChapterTurn = pending" in prepareBody)
        assertTrue("completePageAnimationVisualState" in candidateBody)
        assertTrue("view.evaluateJavascript(MEASURE_VIEWPORT_SCRIPT)" in candidateBody)
        assertTrue("isCandidateActivationVerified(" in candidateBody)
        assertTrue("completeActivation(" in candidateBody)
        assertTrue("val keepOutgoingVisible = chapterTurn != null ||" in activationBody)
        assertTrue("(hasVisibleDocument && view !== currentWebView)" in activationBody)
        assertTrue("activateWebView(view, keepOutgoingVisible = keepOutgoingVisible)" in activationBody)
        assertTrue("hideOutgoingWebView(incomingView)" in turnBody)
        assertTrue(
            turnBody.indexOf("bindLivePageAnimationTarget(") <
                turnBody.indexOf("hideOutgoingWebView(incomingView)") &&
                turnBody.indexOf("hideOutgoingWebView(incomingView)") <
                turnBody.indexOf("revealLivePageAnimationTarget(mounted.overlay)")
        )
        assertTrue("revealActivatedWebViewWithoutAnimation(incomingView)" in turnBody)
        assertTrue("revealActivatedWebViewWithoutAnimation(incomingView)" in interactiveBody)
        assertFalse("restoreInteractiveSource(turn)" in interactiveBody)
        assertTrue(
            candidateBody.indexOf("completePageAnimationVisualState") <
                candidateBody.indexOf("completeActivation")
        )
        assertFalse("chapterTurn.sourceBitmap = captureView(" in activationBody)
        assertFalse("captureView(" in activationBody)
        assertTrue("revealActivatedWebViewWithoutAnimation(view)" in activationBody)
        assertTrue("incoming.doOnPreDraw" in revealBody)
        assertTrue("postOnAnimation(reveal)" in revealBody)
        assertTrue("postDelayed(reveal, FOREGROUND_REVEAL_TIMEOUT_MS)" in revealBody)
        assertTrue("hideOutgoingWebView(incoming)" in revealBody)
        assertTrue("clearRecoverySnapshot()" in revealBody)
        assertTrue("mountPageAnimationOverlay(" in turnBody)
        assertTrue("targetBitmap = targetBitmap" in turnBody)
        assertTrue("bindLivePageAnimationTarget(" in turnBody)
        assertFalse("captureView(" in turnBody)
        assertTrue("startOverlayAnimator(" in turnBody)
        assertFalse("startLiveChapterTurn(" in turnBody)
        assertFalse("html2canvas" in source.lowercase())
    }

    @Test
    fun `activation retries a single generation until its semantic target is renderable`() {
        val source = webLayerSource()
        val readyBody = kotlinFunctionBody(source, "onDocumentReady")
        val probeBody = kotlinFunctionBody(source, "probePendingActivation")
        val candidateBody = kotlinFunctionBody(source, "applyCandidatePage")
        val verificationBody = kotlinFunctionBody(source, "isCandidateActivationVerified")

        assertTrue("pendingActivationView = view" in readyBody)
        assertTrue("scheduleActivationTimeout(view = view, token = token)" in readyBody)
        assertTrue("requestPendingActivationProbe(view = view, token = token, immediate = true)" in readyBody)
        assertTrue("requestPendingActivationProbe(view, token)" in probeBody)
        assertFalse("scheduleActivationTimeout(" in probeBody)
        assertTrue("requestPendingActivationProbe(view, token)" in candidateBody)
        assertFalse("EPUB document produced no renderable content" in source)
        assertTrue("metrics.activationTargetRevision == metrics.layoutRevision" in verificationBody)
    }

    @Test
    fun `committed source capture uses window compositor pixels and rejects stale pages`() {
        val source = webLayerSource()
        val keyBody = kotlinFunctionBody(source, "committedPageSnapshotKey")
        val requestBody = kotlinFunctionBody(source, "requestCommittedPageSnapshot")
        val failureBody = kotlinFunctionBody(source, "reportCommittedPageSnapshotFailure")

        assertTrue("generation = generation" in keyBody)
        assertTrue("token = view.token" in keyBody)
        assertTrue("viewIdentity = System.identityHashCode(view)" in keyBody)
        assertTrue("chapterIndex = activeChapter.chapterIndex" in keyBody)
        assertTrue("pageIndex = pageIndex" in keyBody)
        assertTrue("Build.VERSION.SDK_INT < Build.VERSION_CODES.O" in requestBody)
        assertTrue("PixelCopy.request(" in requestBody)
        assertTrue("currentKey = committedPageSnapshotKey(view)" in requestBody)
        assertTrue("pixel-copy-uniform-background" in requestBody)
        assertTrue("androidScrollX=\${view.scrollX}" in failureBody)
        assertTrue("SNAPSHOT_DIAGNOSTICS_SCRIPT" in failureBody)
    }

    @Test
    fun `isolated renderers borrow sessions and require a fully composed target frame`() {
        val source = webLayerSource()
        val releaseBody = kotlinFunctionBody(source, "releaseSession")
        val probeBody = kotlinFunctionBody(source, "probeCurrentPageFrame")

        assertTrue("internal fun bindBorrowedSession" in source)
        assertTrue("ownsSession = false" in source)
        assertTrue("if (closePreviousSession) previousSession?.close()" in source)
        assertTrue("if (closeSession) closingSession?.close()" in releaseBody)
        assertTrue("EpubDirectFrameReadinessPolicy.isReady(" in probeBody)
        assertTrue("resourcesReady = metrics.resourcesReady" in probeBody)
        assertTrue("completeAfterVisualState(view)" in probeBody)
        assertTrue("completeAfterCompositorFrames(view)" in probeBody)
        assertTrue(
            probeBody.indexOf("completeAfterVisualState(view)") <
                probeBody.indexOf("completeAfterCompositorFrames(view)")
        )
    }

    @Test
    fun `adjacent frame pipeline renders near and far targets independently`() {
        val source = webLayerSource()
        val syncBody = kotlinFunctionBody(source, "syncAdjacentPageFrames")
        val notifyBody = kotlinFunctionBody(source, "notifyPositionChanged")
        val preloadBody = kotlinFunctionBody(source, "preloadChapter")
        val hiddenBody = kotlinFunctionBody(source, "onHidden")
        val destroyBody = kotlinFunctionBody(source, "destroy")
        val takeFrameBody = kotlinFunctionBody(adjacentFramePipelineSource(), "takeFrame")
        val suspendBody = kotlinFunctionBody(adjacentFramePipelineSource(), "suspendScheduling")
        val resumeBody = kotlinFunctionBody(adjacentFramePipelineSource(), "resumeScheduling")
        val offerFrameBody = kotlinFunctionBody(adjacentFramePipelineSource(), "offerFrame")
        val scheduleDirectionBody = kotlinFunctionBody(
            adjacentFramePipelineSource(),
            "scheduleDirection"
        )

        assertTrue("frameRenderer" in syncBody)
        assertTrue("pipeline.bindCurrent(" in syncBody)
        assertTrue("pipeline.offerPreparedChapter(" in syncBody)
        assertTrue(
            notifyBody.indexOf("syncAdjacentPageFrames()") <
                notifyBody.indexOf("listener?.onPositionChanged")
        )
        assertTrue("adjacentPageFrames?.offerPreparedChapter" in preloadBody)
        assertTrue("closeAdjacentPageFrames()" in hiddenBody)
        assertTrue("closeAdjacentPageFrames()" in destroyBody)
        assertFalse("scheduleDesiredTargets()" in takeFrameBody)
        assertTrue("schedulingSuspended = true" in suspendBody)
        assertTrue("schedulingSuspended = false" in resumeBody)
        assertTrue("scheduleDesiredTargets()" in resumeBody)
        assertTrue("if (schedulingSuspended)" in scheduleDirectionBody)
        assertTrue("SlotKey(direction, Distance.Near)" in scheduleDirectionBody)
        assertTrue("SlotKey(direction, Distance.Far)" in scheduleDirectionBody)
        assertFalse("sequenceOf(" in scheduleDirectionBody)
        assertTrue("frameCache.put(target.cacheKey, frame)" in offerFrameBody)
        assertTrue("scheduleDesiredTargets()" in offerFrameBody)
        assertTrue(
            destroyBody.indexOf("closeAdjacentPageFrames()") <
                destroyBody.indexOf("closingSession")
        )
    }

    @Test
    fun `committed snapshot refresh happens only after animation overlay removal`() {
        val body = kotlinFunctionBody(webLayerSource(), "finishPageAnimation")

        assertTrue("releasePageAnimationOverlay(" in body)
        assertTrue("deferTargetLayerRelease = true" in body)
        assertTrue("scheduleAdjacentPageFrameResume()" in body)
        assertTrue("scheduleCommittedPageSnapshotRefresh(" in body)
        assertTrue(
            body.indexOf("releasePageAnimationOverlay(") <
                body.indexOf("scheduleCommittedPageSnapshotRefresh(")
        )
    }

    @Test
    fun `live target handoff cannot feed an old compositor frame back into snapshots`() {
        val webLayer = webLayerSource()
        val blockerBody = kotlinFunctionBody(webLayer, "hasCommittedSnapshotCaptureBlocker")
        val layerReleaseBody = kotlinFunctionBody(webLayer, "scheduleLiveTargetLayerRelease")
        val targetBody = kotlinFunctionBody(pageOverlaySource(), "drawableTargetBitmap")

        assertTrue("deferredLiveTargetLayerRelease != null" in blockerBody)
        assertTrue("scheduleCommittedPageSnapshotRefresh(\"live-target-layer-restored\")" in layerReleaseBody)
        assertTrue("liveTargetRevealed" in targetBody)
        assertTrue("style != EpubDirectPageAnimationPolicy.Style.Simulation" in targetBody)
        assertTrue("action == EpubDirectPageAnimationPolicy.TurnAction.Next" in targetBody)
    }

    @Test
    fun `staged chapters cannot show through a transparent committed page`() {
        val source = webLayerSource()
        val body = kotlinFunctionBody(source, "stageCandidate")
        val affectsBody = kotlinFunctionBody(source, "childAffectsCommittedSnapshotScene")
        val addedBody = kotlinFunctionBody(source, "onViewAdded")
        val removedBody = kotlinFunctionBody(source, "onViewRemoved")
        val stagedAlpha = "candidate.alpha = if (revealedPreview != null) 1f else CANDIDATE_RENDER_ALPHA"

        assertTrue("candidate.visibility = VISIBLE" in body)
        assertTrue(stagedAlpha in body)
        assertTrue("val revealedPreview = livePageAnimationTarget?.takeIf" in body)
        assertTrue("it.view === candidate && it.revealed && it.overlay === pageAnimationOverlay &&" in body)
        assertTrue("it.sequence == pageAnimationSequence && it.boundChapterKey == candidate.loadedChapterKey" in body)
        assertFalse("candidate.alpha = if (keepCurrentVisible)" in body)
        assertTrue("private const val CANDIDATE_RENDER_ALPHA = 0.001f" in source)
        assertTrue("child !is ReaderWebView" in affectsBody)
        assertTrue("child === currentWebView" in affectsBody)
        assertTrue("child.alpha > CANDIDATE_RENDER_ALPHA" in affectsBody)
        assertTrue("childAffectsCommittedSnapshotScene(child)" in addedBody)
        assertTrue("childAffectsCommittedSnapshotScene(child)" in removedBody)
        assertTrue(
            body.indexOf("candidate.visibility = VISIBLE") <
                body.indexOf(stagedAlpha)
        )
    }

    @Test
    fun `animation final frame is removed immediately after its draw`() {
        val source = webLayerSource()
        val body = kotlinFunctionBody(source, "finishPageAnimationAfterFinalFrame")

        assertTrue("OnDrawListener" in body)
        assertTrue("postAtFrontOfQueue(finish)" in body)
        assertTrue("overlay.invalidate()" in body)
        assertTrue("postDelayed(finish, FINAL_FRAME_FALLBACK_MS)" in body)
        assertFalse("doOnPreDraw" in body)
    }

    @Test
    fun `rapid turns keep accepted requests queued until a turn is handled`() {
        val source = webLayerSource()
        val nextBody = kotlinFunctionBody(source, "nextPage")
        val previousBody = kotlinFunctionBody(source, "previousPage")
        val performBody = kotlinFunctionBody(source, "performPageTurn")
        val enqueueBody = kotlinFunctionBody(source, "enqueuePageTurn")
        val drainBody = kotlinFunctionBody(source, "scheduleQueuedPageTurnDrain")
        val activationBody = kotlinFunctionBody(source, "completeActivation")
        val finishBody = kotlinFunctionBody(source, "finishPageAnimation")
        val preserveTargetBody = kotlinFunctionBody(
            source,
            "preserveAnimationTargetAsCommittedSnapshot"
        )
        val targetMatchesBody = kotlinFunctionBody(source, "targetFrameMatchesCommittedPage")
        val chapterTurnBody = kotlinFunctionBody(source, "completeChapterTurn")
        val interactiveChapterBody = kotlinFunctionBody(source, "beginInteractiveChapterTurn")
        val preserveSourceBody = kotlinFunctionBody(
            source,
            "preserveAnimationSourceAsAdjacentFrame"
        )
        val takeSourceBody = kotlinFunctionBody(source, "takeAnimationSourceFrame")
        val preserveBitmapBody = kotlinFunctionBody(source, "preserveBitmapAsCommittedSnapshot")
        val syncBody = kotlinFunctionBody(source, "syncAdjacentPageFrames")
        val materialBody = kotlinFunctionBody(source, "shouldWaitForPageAnimationMaterial")
        val materialTimeoutBody = kotlinFunctionBody(source, "beginAnimationMaterialWait")
        val materialFallbackBody = kotlinFunctionBody(source, "allowAnimationMaterialFallback")
        val snapshotRefreshBody = kotlinFunctionBody(
            source,
            "scheduleCommittedPageSnapshotRefresh"
        )
        val captureBody = kotlinFunctionBody(source, "requestCommittedPageSnapshot")
        val setPageBody = kotlinFunctionBody(source, "setPage")
        val internalSetPageBody = kotlinFunctionBody(source, "setPageFromPageTurn")
        val fragmentBody = kotlinFunctionBody(source, "navigateToFragment")

        assertTrue("logicalDirection = 1" in nextBody)
        assertTrue("logicalDirection = -1" in previousBody)
        assertTrue("queueIfBusy = true" in nextBody)
        assertTrue("queueIfBusy = true" in previousBody)
        assertTrue("enqueuePageTurn(direction)" in performBody)
        assertTrue("queuedPageTurns.enqueue(direction)" in enqueueBody)
        assertTrue("EpubPageTurnQueue(MAX_QUEUED_PAGE_TURN_RUNS)" in source)
        assertTrue("queuedPageTurns.peekDirection()" in drainBody)
        assertTrue("queueIfBusy = false" in drainBody)
        assertTrue("dispatchBoundary(" in drainBody)
        assertTrue("accepted = true" in drainBody)
        assertTrue("queuedPageTurns.markHeadHandled()" in drainBody)
        assertFalse("scheduleQueuedPageTurnDrain(QUEUED_PAGE_TURN_RETRY_MS)" in drainBody)
        assertFalse("postDelayed(drain" in drainBody)
        assertTrue("scheduleQueuedPageTurnDrain()" in activationBody)
        assertTrue(
            drainBody.indexOf("val result = performPageTurn(") <
                drainBody.indexOf("queuedPageTurns.markHeadHandled()")
        )
        assertTrue("preserveAnimationSourceAsAdjacentFrame(overlay)" in finishBody)
        assertTrue("preserveAnimationTargetAsCommittedSnapshot(overlay)" in finishBody)
        assertTrue("if (targetSnapshotReady) scheduleQueuedPageTurnDrain()" in finishBody)
        assertTrue("overlay.takeTargetBitmap()" in preserveTargetBody)
        assertTrue("targetFrameMatchesCommittedPage(metadata)" in preserveTargetBody)
        assertTrue("bitmap.takeUnless { it.isRecycled }?.recycle()" in preserveTargetBody)
        assertTrue("preserveBitmapAsCommittedSnapshot(" in preserveTargetBody)
        assertTrue("metadata.layoutRevision != currentLayoutRevision" in targetMatchesBody)
        assertTrue("metadata.chapterIndex != key.chapterIndex" in targetMatchesBody)
        assertTrue("metadata.pageIndex != key.pageIndex" in targetMatchesBody)
        assertTrue("metadata.layoutSignature != EpubPageFrameTarget.layoutSignature(" in targetMatchesBody)
        assertFalse("targetFrameMetadata =" in chapterTurnBody)
        assertFalse("targetFrameMetadata =" in interactiveChapterBody)
        assertTrue("scheduleCommittedPageSnapshotRefresh(\"page-animation-finished\")" in finishBody)
        assertTrue("shouldWaitForPageAnimationMaterial(direction, animate)" in performBody)
        assertTrue("committedPageSnapshots.contains(key)" in materialBody)
        assertTrue("hasAdjacentPageBitmap(" in materialBody)
        assertTrue("PAGE_ANIMATION_MATERIAL_TIMEOUT_MS" in materialTimeoutBody)
        assertTrue("scheduleQueuedPageTurnDrain()" in materialTimeoutBody)
        assertTrue("if (!queuedPageTurns.isEmpty) scheduleQueuedPageTurnDrain()" in materialFallbackBody)
        assertTrue("allowAnimationMaterialFallback(key)" in snapshotRefreshBody)
        assertTrue("if (committed)" in captureBody)
        assertTrue("scheduleQueuedPageTurnDrain()" in captureBody)
        assertTrue("adjacentPageFrames?.offerFrame(frame)" in preserveSourceBody)
        assertTrue("overlay.takeSourceBitmap()" in takeSourceBody)
        assertTrue("EpubRenderedPageFrame(" in takeSourceBody)
        assertTrue("committedPageSnapshots.complete(" in preserveBitmapBody)
        assertTrue("bitmap.width != key.viewportWidth" in preserveBitmapBody)
        assertTrue("bitmap.height != key.viewportHeight" in preserveBitmapBody)
        assertTrue("it.setListener(" in syncBody)
        assertTrue("scheduleQueuedPageTurnDrain()" in syncBody)
        assertFalse("atFront" in enqueueBody + drainBody)
        assertTrue("if (!internalPageTurnSetPage) clearQueuedPageTurns()" in setPageBody)
        assertTrue("internalPageTurnSetPage = true" in internalSetPageBody)
        assertTrue("clearQueuedPageTurns()" in fragmentBody)
        assertTrue(
            finishBody.indexOf("preserveAnimationSourceAsAdjacentFrame(overlay)") <
                finishBody.indexOf("releasePageAnimationOverlay(")
        )
    }

    @Test
    fun `boundary navigation is independent from animation material and debounce preserves accepted turns`() {
        val source = webLayerSource()
        val performBody = kotlinFunctionBody(source, "performPageTurn")
        val prepareBody = kotlinFunctionBody(source, "prepareChapterTurn")
        val dispatchBody = kotlinFunctionBody(source, "dispatchBoundary")
        val swipeBody = kotlinFunctionBody(source, "onSwipe")
        val busyBody = kotlinFunctionBody(source, "isPageTurnBusy")

        assertTrue("EpubPageTurnResult.BoundaryRequired" in performBody)
        assertTrue("sourceBitmap = sourceBitmap" in prepareBody)
        assertTrue("targetBitmap = targetBitmap" in prepareBody)
        assertFalse(
            Regex("takeCommittedPageSnapshot\\([\\s\\S]*?\\)\\s*\\?: return null")
                .containsMatchIn(prepareBody)
        )
        assertTrue("if (!accepted && isBoundaryDebounced(now)) return false" in dispatchBody)
        assertFalse("cancelPendingChapterTurn()" in dispatchBody)
        assertFalse("style != EpubDirectPageAnimationPolicy.Style.None" in dispatchBody)
        assertTrue("prepareChapterTurn(normalizedDirection, animate = true) ?: return false" in dispatchBody)
        assertFalse("isCommittedPageSnapshotPreparationPending" in busyBody)
        assertFalse("isCommittedPageSnapshotPreparationPending" in source)
        assertTrue(
            performBody.indexOf("val atBoundary") <
                performBody.indexOf("shouldWaitForPageAnimationMaterial")
        )
        assertTrue("if (!isPageTurnBusy() && atBoundary)" in swipeBody)
        assertTrue("dispatchBoundary(direction)" in swipeBody)
    }

    @Test
    fun `activity routes only explicit page boundary results to chapter navigation`() {
        val source = readBookActivitySource()
        val nextBody = kotlinFunctionBody(source, "requestNextEpubPage")
        val previousBody = kotlinFunctionBody(source, "previousEpubPage")

        assertTrue("val result = binding.epubReadView.nextPage()" in nextBody)
        assertTrue("if (result.requiresBoundaryNavigation)" in nextBody)
        assertTrue("binding.epubReadView.previousPage().requiresBoundaryNavigation" in previousBody)
        assertFalse("if (binding.epubReadView.nextPage()) return" in nextBody)
        assertFalse("if (binding.epubReadView.previousPage()) return" in previousBody)
    }

    @Test
    fun `live target follows every interactive and automatic animation frame`() {
        val source = webLayerSource()
        val progressBody = kotlinFunctionBody(source, "setPageAnimationProgress")
        val targetBody = kotlinFunctionBody(source, "updateLivePageAnimationTarget")
        val revealBody = kotlinFunctionBody(source, "revealLivePageAnimationTarget")
        val prepareBody = kotlinFunctionBody(source, "prepareLivePageAnimationTarget")
        val interactiveBody = kotlinFunctionBody(source, "updateInteractivePageTurn")
        val automaticBody = kotlinFunctionBody(source, "startOverlayAnimator")

        assertTrue("overlay.progress = progress" in progressBody)
        assertTrue("updateLivePageAnimationTarget(overlay.progress)" in progressBody)
        assertTrue("target.view.translationX" in targetBody)
        assertTrue("if (target.revealed)" in targetBody)
        assertTrue("liveTargetTranslationX(" in targetBody)
        assertTrue("target.revealed = true" in revealBody)
        assertTrue(
            revealBody.indexOf("prepareLivePageAnimationTarget(target)") <
                revealBody.indexOf("target.revealed = true")
        )
        assertTrue("requiresMovingLiveTarget" in prepareBody)
        assertTrue("interactivePageTurn?.overlay === target.overlay" in prepareBody)
        assertTrue("View.LAYER_TYPE_HARDWARE" in prepareBody)
        assertTrue("target.view.buildLayer()" in prepareBody)
        assertTrue(
            prepareBody.indexOf("interactivePageTurn?.overlay === target.overlay") <
                prepareBody.indexOf("target.view.buildLayer()")
        )
        assertTrue(
            revealBody.indexOf("updateLivePageAnimationTarget") <
                revealBody.indexOf("overlay.revealLiveTarget()")
        )
        assertTrue("setPageAnimationProgress(" in interactiveBody)
        assertTrue("setPageAnimationProgress(" in automaticBody)
        assertTrue("overlay," in automaticBody)
        assertTrue("pageAnimationInterpolator(style)" in automaticBody)
        assertTrue("settledProgress(" in automaticBody)
    }

    @Test
    fun `simulation assigns folding and underlying bitmaps by logical action`() {
        val overlayBody = kotlinFunctionBody(pageOverlaySource(), "drawSimulation")
        val renderer = simulationRendererSource()
        val rendererBody = kotlinFunctionBody(renderer, "draw")

        assertTrue("TurnAction.Next -> sourceBitmap" in overlayBody)
        assertTrue("TurnAction.Previous -> target ?: return" in overlayBody)
        assertTrue("TurnAction.Next -> target" in overlayBody)
        assertTrue("TurnAction.Previous -> sourceBitmap" in overlayBody)
        assertTrue("foldingBitmap = foldingBitmap" in overlayBody)
        assertTrue("underlyingBitmap = underlyingBitmap" in overlayBody)
        assertFalse("drawCover(" in overlayBody)
        assertTrue("drawCurrentPageArea(canvas, foldingBitmap)" in rendererBody)
        assertTrue("underlyingBitmap?.takeUnless" in rendererBody)
        assertFalse("if (direction > 0)" in rendererBody)
        assertTrue("simulationMotion.updateFrame(" in overlayBody)
    }

    @Test
    fun `dual frame overlay never duplicates the source as a missing target`() {
        val source = pageOverlaySource()
        val coverBody = kotlinFunctionBody(source, "drawCover")
        val slideBody = kotlinFunctionBody(source, "drawSlide")
        val linkedBody = kotlinFunctionBody(source, "drawLinkedCover")
        val simulationBody = kotlinFunctionBody(source, "drawSimulation")
        val drawableTargetBody = kotlinFunctionBody(source, "drawableTargetBitmap")
        val revealBody = kotlinFunctionBody(source, "revealLiveTarget")
        val releaseBody = kotlinFunctionBody(source, "release")

        assertTrue("private val targetBitmap: Bitmap?" in source)
        for (body in listOf(coverBody, slideBody, linkedBody)) {
            assertTrue("drawTarget(canvas, frame)" in body)
            assertTrue(
                body.indexOf("drawTarget(canvas, frame)") <
                    body.indexOf("drawSource(canvas, frame)")
            )
        }
        assertTrue("val target = drawableTargetBitmap()" in simulationBody)
        assertTrue("underlyingBitmap = underlyingBitmap" in simulationBody)
        assertTrue("targetBitmap?.takeUnless" in drawableTargetBody)
        assertFalse("sourceBitmap.takeUnless" in drawableTargetBody)
        assertFalse("?: sourceBitmap" in drawableTargetBody)
        assertTrue("liveTargetRevealed" in drawableTargetBody)
        assertTrue("style != EpubDirectPageAnimationPolicy.Style.Simulation" in drawableTargetBody)
        assertTrue("liveTargetRevealed = true" in revealBody)
        assertTrue("targetTransferred" in releaseBody)
        assertTrue("targetBitmap" in releaseBody)
    }

    @Test
    fun `simulation previous degrades motion when its folding target frame is absent`() {
        val source = webLayerSource()
        val automatic = kotlinFunctionBody(source, "startPageAnimation")
        val interactive = kotlinFunctionBody(source, "beginInteractivePageTurn")
        val interactiveBoundary = kotlinFunctionBody(source, "beginInteractiveChapterTurn")
        val chapterCommit = kotlinFunctionBody(source, "completeChapterTurn")

        for (body in listOf(automatic, interactive, interactiveBoundary, chapterCommit)) {
            assertTrue("hasRequiredBitmapFrames(" in body)
        }
        assertTrue("simulation-previous-target-unavailable" in chapterCommit)
        assertTrue("return false" in automatic)
        assertTrue("return false" in interactive)
        assertTrue("return false" in interactiveBoundary)
    }

    @Test
    fun `image enlargement is armed only by an unmoved long press`() {
        val source = runtimeSource()
        val mediaBody = functionBody(source, "prepareInteractiveMedia")
        val longPressBody = functionBody(source, "showLongPressedImage")
        val touchStartBody = documentEventListenerBody(source, "touchstart")
        val clickBody = documentEventListenerBody(source, "click")
        val contextMenuBody = documentEventListenerBody(source, "contextmenu")

        assertTrue("var IMAGE_LONG_PRESS_MS=500" in source)
        assertTrue("setTimeout(function(){showLongPressedImage(state);},IMAGE_LONG_PRESS_MS)" in touchStartBody)
        assertTrue("state.moved||state.opened" in longPressBody)
        assertTrue("imageOverlay(src)" in longPressBody)
        assertTrue("onImage(activeToken,src)" in longPressBody)
        assertFalse("imageOverlay(" in mediaBody)
        assertFalse("onImage(" in mediaBody)
        assertTrue("Date.now()<suppressImageClickUntil" in clickBody)
        assertFalse("imageOverlay(" in clickBody)
        assertTrue("imageOverlay(src)" in contextMenuBody)
        assertTrue("imageLongPress.opened" in contextMenuBody)
        assertTrue("Date.now()<suppressImageClickUntil" in contextMenuBody)
    }

    @Test
    fun `publisher media controls are preserved while duokan audio uses its image states`() {
        val source = runtimeSource()
        val mediaBody = functionBody(source, "prepareInteractiveMedia")
        val duokanBody = functionBody(source, "prepareDuokanAudio")
        val imageTargetBody = functionBody(source, "imageTarget")
        val webLayer = webLayerSource()

        assertFalse("setAttribute('controls'" in mediaBody)
        assertTrue("prepareDuokanAudio(media)" in mediaBody)
        assertTrue("mediaAttributeUrl(media,'placeholder')" in duokanBody)
        assertTrue("mediaAttributeUrl(media,'activestate')" in duokanBody)
        assertTrue("media.removeAttribute('controls')" in duokanBody)
        assertTrue("media.play()" in duokanBody)
        assertTrue("media.pause()" in duokanBody)
        assertTrue("runtimeMediaControlNode(image)" in imageTargetBody)
        assertTrue("runtimeMediaControlMutation(mutation)" in source)
        assertTrue("runtimeMediaElement(target)" in source)
        assertTrue("webChromeClient = WebChromeClient()" in webLayer)
    }

    @Test
    fun `animation cleanup always resets the live target translation`() {
        val source = webLayerSource()
        val clearBody = kotlinFunctionBody(source, "clearLivePageAnimationTarget")
        val releaseBody = kotlinFunctionBody(source, "releasePageAnimationOverlay")
        val finishBody = kotlinFunctionBody(source, "finishPageAnimation")
        val cancelBody = kotlinFunctionBody(source, "cancelPageAnimation")
        val restoreLayerBody = kotlinFunctionBody(source, "restoreLiveTargetLayer")
        val scheduleReleaseBody = kotlinFunctionBody(source, "scheduleLiveTargetLayerRelease")

        assertTrue("target.view.translationX = 0f" in clearBody)
        assertTrue("scheduleLiveTargetLayerRelease(" in clearBody)
        assertTrue("target.view.setHasTransientState(false)" in clearBody)
        assertTrue("view.setLayerType(originalLayerType" in restoreLayerBody)
        assertTrue("completeAfterVisualState(view)" in scheduleReleaseBody)
        assertTrue("LIVE_TARGET_LAYER_RELEASE_TIMEOUT_MS" in scheduleReleaseBody)
        assertFalse("LIVE_TARGET_LAYER_RELEASE_DELAY_MS" in source)
        assertTrue("clearLivePageAnimationTarget(" in releaseBody)
        assertTrue("deferTargetLayerRelease = true" in finishBody)
        assertTrue("flushDeferredLiveTargetLayerRelease()" in cancelBody)
        assertTrue("releasePageAnimationOverlay(overlay)" in cancelBody)
    }

    @Test
    fun `viewport session and lifecycle invalidation discard queued and rendered frames`() {
        val source = webLayerSource()
        val sizeBody = kotlinFunctionBody(source, "onSizeChanged")
        val privateBindStart = source.indexOf("private fun bindSession(")
        val bindBody = bracedBody(source, privateBindStart, "private bindSession")
        val pauseBody = kotlinFunctionBody(source, "onHostPause")
        val trimBody = kotlinFunctionBody(source, "trimMemory")
        val hiddenBody = kotlinFunctionBody(source, "onHidden")

        for (body in listOf(sizeBody, bindBody, pauseBody, trimBody, hiddenBody)) {
            assertTrue("closeAdjacentPageFrames()" in body)
            assertTrue("clearQueuedPageTurns()" in body)
        }
    }

    @Test
    fun `cover edge uses a narrow direction aware gradient`() {
        val source = pageOverlaySource()
        val shadowBody = kotlinFunctionBody(source, "drawEdgeShadow")

        assertTrue("LinearGradient(" in shadowBody)
        assertTrue("if (shadowDirection > 0)" in shadowBody)
        assertTrue("easedOpacity" in shadowBody)
        assertTrue("shadowPaint.shader = null" in shadowBody)
        assertFalse("shadowPaint.color" in source)
    }

    @Test
    fun `linked cover draws parallax masks instead of fading the source`() {
        val body = kotlinFunctionBody(pageOverlaySource(), "drawLinkedCover")

        assertTrue("frame.targetMaskAlpha" in body)
        assertTrue("drawMask(" in body)
        assertTrue("drawSource(canvas, frame)" in body)
        assertTrue("drawEdgeShadow(" in body)
        assertFalse("1f - progress" in body)
    }

    @Test
    fun `page and chapter turns share the explicit logical action contract`() {
        val source = webLayerSource()
        val automatic = kotlinFunctionBody(source, "startPageAnimation")
        val interactive = kotlinFunctionBody(source, "beginInteractivePageTurn")
        val interactiveBoundary = kotlinFunctionBody(source, "beginInteractiveChapterTurn")
        val chapterCommit = kotlinFunctionBody(source, "completeChapterTurn")
        val interactiveChapterCommit = kotlinFunctionBody(source, "completeInteractiveChapterActivation")

        assertTrue("turnAction(logicalDirection)" in automatic)
        assertTrue("turnAction(logicalDirection)" in interactive)
        assertTrue("turnAction(logicalDirection)" in interactiveBoundary)
        assertTrue("turnAction(turn.logicalDirection)" in chapterCommit)
        assertTrue("turnAction(turn.logicalDirection)" in interactiveChapterCommit)
    }

    @Test
    fun `reader css preserves publisher root and body backgrounds`() {
        val body = kotlinFunctionBody(documentBuilderSource(), "readerCss")

        assertFalse("background:\$background!important" in body)
        assertFalse("background:transparent!important" in body)
        assertFalse(Regex("html\\{[^}]*background:transparent").containsMatchIn(body))
        assertTrue("background:#000!important" in body)
    }

    @Test
    fun `reader selection uses the app theme and suppresses the WebView action menu`() {
        val builder = documentBuilderSource()
        val activity = readBookActivitySource()
        val readView = epubReadViewSource()
        val webLayer = webLayerSource()

        assertTrue("color(config.selectionColor)" in builder)
        assertTrue("R.color.btn_bg_press_2" in activity)
        assertFalse("rgba(56,168,255" in builder)
        assertTrue("menu.clear()" in webLayer)
        assertFalse("ActionMode.Callback2" in webLayer)
        assertTrue("postDelayed(selectionMenuNotifyRunnable, SELECTION_RELEASE_SETTLE_MS)" in readView)
        assertTrue("const val SELECTION_RELEASE_SETTLE_MS = 64L" in readView)
        assertTrue("override fun onSelectionInteractionStarted()" in activity)
        assertTrue("hideEpubSelectionUi()" in activity)
    }

    @Test
    fun `active or pending text selection blocks every EPUB page turn entry point`() {
        val webLayer = webLayerSource()
        val readView = epubReadViewSource()
        val activity = readBookActivitySource()
        val performTurn = kotlinFunctionBody(webLayer, "performPageTurn")
        val dispatchBoundary = kotlinFunctionBody(webLayer, "dispatchBoundary")
        val reportSelection = kotlinFunctionBody(webLayer, "reportSelection")
        val touch = kotlinFunctionBody(webLayer, "onTouchEvent")
        val updateLongPress = kotlinFunctionBody(webLayer, "updateLongPressSelectionGesture")
        val nextPage = kotlinFunctionBody(readView, "nextPage")
        val previousPage = kotlinFunctionBody(readView, "previousPage")
        val nextEpubPage = kotlinFunctionBody(activity, "requestNextEpubPage")
        val previousEpubPage = kotlinFunctionBody(activity, "previousEpubPage")

        assertTrue(
            performTurn.indexOf("if (isSelectionPageTurnBlocked())") <
                performTurn.indexOf("if (isPageTurnBusy())")
        )
        assertTrue("if (isSelectionPageTurnBlocked()) return false" in dispatchBoundary)
        assertTrue("clearQueuedPageTurns()" in reportSelection)
        assertTrue("selectionActive || currentWebView.hasLongPressSelectionGesture()" in webLayer)
        assertTrue("postDelayed(arm, ViewConfiguration.getLongPressTimeout().toLong())" in webLayer)
        assertTrue("EpubDirectGesturePolicy.startsLongPressSelection(" in updateLongPress)
        assertTrue("elapsedMillis = now - downAt" in updateLongPress)
        assertTrue("timeoutMillis = ViewConfiguration.getLongPressTimeout().toLong()" in updateLongPress)
        assertTrue(touch.indexOf("downAt = SystemClock.uptimeMillis()") < touch.indexOf("armLongPressSelectionGesture()"))
        assertTrue("clearQueuedPageTurns()" in updateLongPress)
        assertTrue("postDelayed(unlock, SELECTION_REPORT_SETTLE_MS)" in webLayer)
        assertTrue("private const val SELECTION_REPORT_SETTLE_MS = 96L" in webLayer)
        assertTrue("if (isSelectionBlockingPageTurn) return EpubPageTurnResult.Rejected" in nextPage)
        assertTrue("if (isSelectionBlockingPageTurn) return EpubPageTurnResult.Rejected" in previousPage)
        assertTrue("if (binding.epubReadView.isSelectionBlockingPageTurn)" in nextEpubPage)
        assertTrue("return EpubPageTurnResult.Rejected" in nextEpubPage)
        assertTrue("if (binding.epubReadView.isSelectionBlockingPageTurn) return" in previousEpubPage)
    }

    @Test
    fun `direct epub exposes classic chrome but disables native advanced modes`() {
        val activity = readBookActivitySource()
        val configBody = kotlinFunctionBody(activity, "buildEpubReaderChromeConfig")
        val dialog = tipConfigDialogSource()

        assertTrue("EpubReaderChromeModePolicy.isSupported(" in configBody)
        assertTrue("directEpub = true" in configBody)
        assertTrue("ReadTipConfig.HEADER_MODE_ADVANCED" in configBody)
        assertTrue("ReadTipConfig.FOOTER_MODE_ADVANCED" in configBody)
        assertTrue("ReadBook.book?.usesDirectReader == true" in dialog)
        assertTrue("if (!directEpub) TipSection" in dialog)
        assertTrue("EpubReaderChromeModePolicy.selectableModes(" in dialog)
        assertTrue("val titleModeOptions = if (directEpub)" in dialog)
        assertTrue("enabled = true" in dialog)
        assertTrue("stringResource(R.string.disabled)" in dialog)
        assertTrue("!directEpub && headerMode == ReadTipConfig.HEADER_MODE_ADVANCED" in dialog)
        assertTrue("!directEpub && footerMode == ReadTipConfig.FOOTER_MODE_ADVANCED" in dialog)
    }

    @Test
    fun `failed resources avoid cache while content addressed reader fonts are immutable`() {
        val source = webLayerSource()
        val body = kotlinFunctionBody(source, "nonCacheableResourceHeaders")
        val readerFontBody = kotlinFunctionBody(source, "readerFontResponse")
        val readerFontHeadersBody = kotlinFunctionBody(source, "readerFontHeaders")

        assertTrue("\"Cache-Control\" to \"no-store, max-age=0\"" in body)
        assertTrue("\"Pragma\" to \"no-cache\"" in body)
        assertTrue("\"Access-Control-Allow-Origin\" to \"*\"" in body)
        assertTrue("readerFontHeaders(preparedConfig)" in readerFontBody)
        assertTrue("\"Cache-Control\" to \"public, max-age=31536000, immutable\"" in readerFontHeadersBody)
        assertTrue("\"Content-Length\"" in readerFontHeadersBody)
    }

    private fun runtimeSource(): String {
        val file = sequenceOf(
            File("src/main/assets/epub/direct-runtime.js"),
            File("app/src/main/assets/epub/direct-runtime.js")
        ).firstOrNull(File::isFile) ?: error("EPUB Direct runtime asset not found")
        return file.readText()
    }

    private fun webLayerSource(): String {
        val relativePath = "io/legado/app/ui/book/read/epub/EpubDirectWebLayer.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("EPUB Direct WebLayer source not found")
        return file.readText()
    }

    private fun readBookActivitySource(): String {
        val relativePath = "io/legado/app/ui/book/read/ReadBookActivity.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("ReadBookActivity source not found")
        return file.readText()
    }

    private fun readMenuSource(): String {
        val relativePath = "io/legado/app/ui/book/read/ReadMenu.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("ReadMenu source not found")
        return file.readText()
    }

    private fun searchMenuSource(): String {
        val relativePath = "io/legado/app/ui/book/read/SearchMenu.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("SearchMenu source not found")
        return file.readText()
    }

    private fun epubReadViewSource(): String {
        val relativePath = "io/legado/app/ui/book/read/epub/EpubReadView.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("EPUB ReadView source not found")
        return file.readText()
    }

    private fun readStyleDialogSource(): String {
        val relativePath = "io/legado/app/ui/book/read/config/ReadStyleDialog.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("ReadStyleDialog source not found")
        return file.readText()
    }

    private fun tipConfigDialogSource(): String {
        val relativePath = "io/legado/app/ui/book/read/config/TipConfigDialog.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("TipConfigDialog source not found")
        return file.readText()
    }

    private fun documentBuilderSource(): String {
        val relativePath =
            "io/legado/app/model/localBook/epubcore/direct/EpubDirectDocumentBuilder.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("EPUB Direct document builder source not found")
        return file.readText()
    }

    private fun pageOverlaySource(): String {
        val relativePath =
            "io/legado/app/ui/book/read/epub/EpubDirectPageAnimationOverlay.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("EPUB Direct page overlay source not found")
        return file.readText()
    }

    private fun adjacentFramePipelineSource(): String {
        val relativePath =
            "io/legado/app/ui/book/read/epub/EpubAdjacentPageFramePipeline.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("EPUB adjacent frame pipeline source not found")
        return file.readText()
    }

    private fun epubSource(fileName: String): String {
        val relativePath = "io/legado/app/ui/book/read/epub/$fileName"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("EPUB source not found: $fileName")
        return file.readText()
    }

    private fun simulationRendererSource(): String {
        val relativePath =
            "io/legado/app/ui/book/read/epub/EpubSimulationTurnRenderer.kt"
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("EPUB simulation renderer source not found")
        return file.readText()
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("function $name(")
        check(start >= 0) { "Function not found: $name" }
        return bracedBody(source, start, name)
    }

    private fun kotlinFunctionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        check(start >= 0) { "Kotlin function not found: $name" }
        return bracedBody(source, start, name)
    }

    private fun assertStableRenderStateVerification(source: String, verificationBody: String) {
        assertTrue("if (!isMeasuredRenderStateCurrent(view, metrics)) return false" in verificationBody)
        // This guard has an expression body, so bracedBody would extract the next function.
        val start = source.indexOf("private fun isMeasuredRenderStateCurrent(")
        check(start >= 0) { "Render state verification function not found" }
        val renderStateBody = source.substring(start).lineSequence()
            .takeWhile { it.isNotBlank() }
            .joinToString("\n")
        assertTrue("!metrics.layoutPending" in renderStateBody)
        assertTrue("!view.renderState.layoutPending" in renderStateBody)
        assertTrue("metrics.visualRevision >= view.renderState.visualRevision" in renderStateBody)
        assertFalse("metrics.resourcesReady" in renderStateBody)
    }

    private fun documentEventListenerBody(source: String, event: String): String {
        val marker = "document.addEventListener('$event',function(e){"
        val start = source.indexOf(marker)
        check(start >= 0) { "Document event listener not found: $event" }
        return bracedBody(source, start, event)
    }

    private fun bracedBody(source: String, start: Int, name: String): String {
        val bodyStart = source.indexOf('{', start)
        check(bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart + 1, index)
            }
        }
        error("Unterminated function: $name")
    }
}
