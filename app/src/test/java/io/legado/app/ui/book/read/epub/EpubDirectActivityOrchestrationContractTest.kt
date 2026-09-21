package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EpubDirectActivityOrchestrationContractTest {

    @Test
    fun `stale direct failure returns before any navigation state mutation`() {
        val body = activityFunctionBody("finishDirectEpubFailure")
        val ownershipCheck = body.indexOf("EpubDirectRequestCommitPolicy.ownsActiveNavigation(")
        val staleLog = body.indexOf("EPUB direct stale failure ignored")
        val staleReturn = body.indexOf("return", startIndex = staleLog)

        assertTrue(ownershipCheck >= 0)
        assertTrue(staleLog > ownershipCheck)
        assertTrue(staleReturn > staleLog)
        listOf(
            "binding.epubReadView.cancelPendingBoundaryTurn()",
            "epubCoreLayoutReadyActionGate.cancel()",
            "epubCoreLoadJob = null",
            "epubDirectOnFinish = null",
            "epubDirectCallbackRequestSeq = 0L",
            "epubCoreLoading = false",
            "epubCoreLoadingChapterIndex = null",
            "epubCoreForegroundTarget = null",
            "epubCoreBoundaryTransition = false",
            "epubCoreSuppressProgressSync = false",
            "binding.epubReadView.clearLoading()"
        ).forEach { mutation ->
            assertTrue("mutation must follow stale return: $mutation", body.indexOf(mutation) > staleReturn)
        }
    }

    @Test
    fun `owned direct failure clears the whole transaction before rendering an outcome`() {
        val body = activityFunctionBody("finishDirectEpubFailure")
        val outcomeBranch = body.indexOf("when (failureAction)")

        assertTrue(outcomeBranch >= 0)
        listOf(
            "epubCoreLoadJob = null",
            "epubDirectOnFinish = null",
            "epubDirectCallbackRequestSeq = 0L",
            "epubCoreLoading = false",
            "epubCoreLoadingChapterIndex = null",
            "epubCoreForegroundTarget = null",
            "epubCoreBoundaryTransition = false",
            "epubCoreSuppressProgressSync = false",
            "binding.epubReadView.clearLoading()"
        ).forEach { cleanup ->
            val cleanupIndex = body.indexOf(cleanup)
            assertTrue("missing cleanup: $cleanup", cleanupIndex >= 0)
            assertTrue("cleanup must precede outcome branch: $cleanup", cleanupIndex < outcomeBranch)
        }
        assertFalse("PreserveLayerState" in body)
        assertTrue(body.indexOf("contentLoadFinish()") > outcomeBranch)
        assertTrue(body.indexOf("onFinish?.invoke()") > body.indexOf("contentLoadFinish()"))
    }

    @Test
    fun `configuration refresh and initial navigation share one generation safe layout gate`() {
        val scheduleBody = activityFunctionBody("scheduleEpubCoreLayoutAction")
        val refreshBody = activityFunctionBody("refreshEpubCoreAfterConfigurationChange")
        val cancelBody = activityFunctionBody("cancelActiveEpubCoreNavigation")

        val submit = scheduleBody.indexOf("epubCoreLayoutReadyActionGate.submit(onReady)")
        val requestLayout = scheduleBody.indexOf("epubReadView.requestLayout()")
        val register = scheduleBody.indexOf("epubReadView.doOnLayout")
        val consume = scheduleBody.indexOf("epubCoreLayoutReadyActionGate.consume(registrationId)?.invoke()")
        assertTrue(submit >= 0)
        assertTrue(requestLayout > submit)
        assertTrue(register > requestLayout)
        assertTrue(consume > register)
        assertTrue("scheduleEpubCoreLayoutAction(" in refreshBody)
        assertFalse("doOnLayout" in refreshBody)
        assertTrue(cancelBody.indexOf("epubCoreLayoutReadyActionGate.cancel()") >= 0)
        assertTrue(
            cancelBody.indexOf("epubCoreLayoutReadyActionGate.cancel()") <
                cancelBody.indexOf("epubCoreLoading = false")
        )
    }

    @Test
    fun `page animation changes preserve a live horizontal direct document`() {
        val body = activityFunctionBody("applyPageAnimationChange")
        val directMode = body.indexOf("if (!isEpubCoreMode())")
        val layoutDecision = body.indexOf("EpubDirectPageAnimationPolicy.requiresLayoutReload(")
        val inPlaceRefresh = body.indexOf("binding.epubReadView.refreshDirectPageAnimationStyle()")
        val inactiveLoad = body.indexOf("loadEpubCoreContent(", inPlaceRefresh)
        val inPlaceReturn = body.indexOf("return@runOnUiThread", inPlaceRefresh)
        val preloadCancellation = body.indexOf("cancelEpubCorePrefetch()")
        val loadingRestart = body.indexOf("epubCoreLoading -> loadEpubCoreContent(")
        val layoutReload = body.indexOf("applyEpubRendererStyleOnly()")

        assertTrue(directMode >= 0)
        assertTrue(layoutDecision > directMode)
        assertTrue(inPlaceRefresh > layoutDecision)
        assertTrue(inactiveLoad > inPlaceRefresh)
        assertTrue(inPlaceReturn > inactiveLoad)
        assertTrue(preloadCancellation > inPlaceReturn)
        assertTrue(loadingRestart > preloadCancellation)
        assertTrue(layoutReload > loadingRestart)
        assertEquals(3, body.countOccurrences("loadEpubCoreContent("))
    }

    @Test
    fun `every page animation picker delegates without reloading content itself`() {
        val activity = activitySource()
        assertEquals(
            2,
            activity.countOccurrences("showPageAnimConfig(::applyPageAnimationChange)")
        )

        val styleDialog = sourceFile(
            "io/legado/app/ui/book/read/config/ReadStyleDialog.kt"
        )
        val stylePicker = functionBody(styleDialog, "AnimAndToolsSection")
        assertTrue("callBack?.applyPageAnimationChange(previousPageAnim)" in stylePicker)
        assertFalse("ReadBook.loadContent(false)" in stylePicker)

        val autoReadDialog = sourceFile(
            "io/legado/app/ui/book/read/config/AutoReadDialog.kt"
        )
        assertTrue("?.applyPageAnimationChange(previousPageAnim)" in autoReadDialog)
        assertFalse("ReadBook.loadContent(false)" in autoReadDialog)

        val baseActivity = sourceFile(
            "io/legado/app/ui/book/read/BaseReadBookActivity.kt"
        )
        val selector = functionBody(baseActivity, "showPageAnimConfig")
        assertTrue("val previousPageAnim = ReadBook.pageAnim()" in selector)
        assertTrue("if (ReadBook.pageAnim() != previousPageAnim)" in selector)
        assertTrue("success(previousPageAnim)" in selector)
    }

    @Test
    fun `generic page animation refresh does not wake the hidden text reader for EPUB`() {
        val body = activityFunctionBody("upPageAnim")
        val guard = body.indexOf("if (!isEpubCoreMode())")
        val hiddenReader = body.indexOf("binding.readView.upPageAnim(upRecorder)")
        val directRefresh = body.indexOf("binding.epubReadView.refreshDirectPageAnimationStyle()")

        assertTrue(guard >= 0)
        assertTrue(hiddenReader > guard)
        assertTrue(directRefresh > hiddenReader)
    }

    @Test
    fun `boundary preemption preserves an accepted turn while explicit navigation cancels it`() {
        val directionBody = activityFunctionBody("requestEpubCoreChapterByDirection")
        val requestBody = activityFunctionBody("requestEpubCoreChapter")
        val nextBody = activityFunctionBody("requestNextEpubPage")
        val previousBody = activityFunctionBody("previousEpubPage")

        assertTrue("epubCoreBoundaryTransition = target.boundaryTransition" in directionBody)
        assertTrue("boundaryTransition = target.boundaryTransition" in directionBody)
        assertTrue("target.boundaryTransition && epubCoreLoading" in directionBody)
        assertTrue("pendingBoundaryChapterIndex(direction)" in directionBody)
        assertFalse("?: adjacentReadableEpubChapterIndex" in directionBody)
        assertTrue("if (!boundaryTransition) binding.epubReadView.cancelPendingBoundaryTurn()" in requestBody)
        assertTrue("cancelActiveEpubCoreNavigation(clearBoundary = !boundaryTransition)" in requestBody)
        assertFalse("cancelActiveEpubCoreNavigation(clearBoundary = true)" in requestBody)
        for (body in listOf(nextBody, previousBody)) {
            val pendingCheck = body.indexOf("hasPendingBoundaryTurn(")
            val pendingReturn = body.indexOf("return", startIndex = pendingCheck)
            val request = body.indexOf("requestEpubCoreChapterByDirection(", startIndex = pendingReturn)
            assertTrue(pendingCheck >= 0)
            assertTrue(pendingReturn > pendingCheck)
            assertTrue(request > pendingReturn)
        }
    }

    @Test
    fun `visible direct document stays visible while the next chapter is prepared`() {
        val body = activityFunctionBody("loadDirectEpubContent")
        val visibleCheck = body.indexOf("val hadVisibleDocument = binding.epubReadView.hasDirectContent")
        val reusableConfig = body.indexOf("val reusableBoundaryConfig = binding.epubReadView.layoutConfig?.takeIf")
        val initialLoadingGuard = body.indexOf("if (!hadVisibleDocument)")
        val showInitialLoading = body.indexOf("showInitialEpubCoreLoadingPage(baseConfig)")

        assertTrue(visibleCheck >= 0)
        assertTrue(reusableConfig > visibleCheck)
        assertTrue("effectiveBoundaryTransition && hadVisibleDocument" in body)
        assertTrue(initialLoadingGuard > reusableConfig)
        assertTrue(showInitialLoading > initialLoadingGuard)
        assertTrue(body.indexOf("showInitialEpubCoreLoadingPage(baseConfig)", showInitialLoading + 1) < 0)
    }

    @Test
    fun `direct prefetch reuses the committed layout config without rebuilding reader background`() {
        val body = activityFunctionBody("scheduleDirectEpubPrefetch")

        assertTrue("val config = binding.epubReadView.layoutConfig ?: return" in body)
        assertFalse("buildEpubCoreLayoutConfig" in body)
        assertFalse("prepareEpubReaderBackground" in body)
        assertFalse("upEpubRendererStyle" in body)
    }

    @Test
    fun `direct read aloud keeps speech coordinates separate from visual pages`() {
        val syncBody = activityFunctionBody("syncEpubCoreProgress")
        val speechGuard = syncBody.indexOf("if (BaseReadAloudService.isRun && isEpubCoreMode())")
        val speechReturn = syncBody.indexOf("return", speechGuard)

        assertTrue(speechGuard >= 0)
        assertTrue(speechReturn > speechGuard)
        assertTrue(syncBody.indexOf("updateDirectStoredPosition(chapterIndex, chapterPageIndex)") > speechReturn)
        val positionBody = activityFunctionBody("updateDirectStoredPosition")
        assertTrue("if (book.isEpub)" in positionBody)
        assertTrue("ReadBook.durChapterPos = chapterPageIndex" in positionBody)
        assertTrue("binding.epubReadView.currentDirectTextPosition()" in positionBody)
        assertTrue("ReadBook.durChapterIndex = chapterIndex" in positionBody)

        for (name in listOf("commitEpubCoreDisplayedChapter", "finishEpubCoreInstantNavigation")) {
            val body = activityFunctionBody(name)
            assertTrue("$name must preserve speech position", "preserveReadAloudPosition" in body)
            assertTrue("$name must guard chapter assignment", "if (!preserveReadAloudPosition)" in body)
            assertTrue("$name must guard progress persistence", "if (!preserveReadAloudPosition) ReadBook.saveRead" in body)
        }
    }

    @Test
    fun `direct read aloud follows only after user and selection locks clear`() {
        val applyBody = activityFunctionBody("applyDirectReadAloudProgress")
        val userLock = applyBody.indexOf("ReadBook.isReadAloudUserNavigationActive()")
        val selectionLock = applyBody.indexOf("binding.epubReadView.isSelectionBlockingPageTurn")
        val chapterRequest = applyBody.indexOf("requestDirectReadAloudChapter(")
        val cueFollow = applyBody.indexOf("binding.epubReadView.followReadAloud(")

        assertTrue(userLock >= 0)
        assertTrue(selectionLock > userLock)
        assertTrue(chapterRequest > selectionLock)
        assertTrue(cueFollow > chapterRequest)
        assertFalse("target ownership must not suppress a needed retry",
            "directReadAloudTargetChapterIndex == progress.chapterIndex" in
                applyBody.substringBefore("requestDirectReadAloudChapter("))
    }

    @Test
    fun `paused direct read aloud restores from the speech character position`() {
        val body = activityFunctionBody("directReadAloudInitialProgress")

        assertTrue("BaseReadAloudService.isRun" in body)
        assertFalse("BaseReadAloudService.isPlay()" in body)
        assertTrue("ReadBook.durChapterPos" in body)
    }

    @Test
    fun `direct read aloud consumes an instant chapter handoff and ignores late stop progress`() {
        val instantBody = activityFunctionBody("finishEpubCoreInstantNavigation")
        assertTrue(
            instantBody.indexOf("consumePendingDirectReadAloudProgress(chapterIndex)") <
                instantBody.indexOf("scheduleDirectEpubPrefetch(chapterIndex, requestSeq)")
        )

        val observeBody = activityFunctionBody("observeLiveBus")
        val directProgressBranch = observeBody.indexOf("if (isEpubCoreMode())", startIndex =
            observeBody.indexOf("EventBus.READ_ALOUD_PROGRESS"))
        val runningCheck = observeBody.indexOf("if (!BaseReadAloudService.isRun) return@observeEvent",
            startIndex = directProgressBranch)
        val pendingMutation = observeBody.indexOf("pendingDirectReadAloudProgress = progress",
            startIndex = directProgressBranch)

        assertTrue(directProgressBranch >= 0)
        assertTrue(runningCheck > directProgressBranch)
        assertTrue(pendingMutation > runningCheck)
        assertTrue("persistDirectVisualProgress()" in observeBody)
    }

    @Test
    fun `starting direct read aloud never reads from the hidden ordinary page`() {
        val body = activityFunctionBody("startDirectEpubReadAloud")

        assertFalse("binding.readView" in body)
        assertTrue("upContent = false" in body)
        assertTrue("fromReadAloud = true" in body)
        assertTrue(body.countOccurrences("binding.epubReadView.currentPage()") >= 2)
    }

    private fun activityFunctionBody(name: String): String {
        val source = activitySource()
        val start = source.indexOf("fun $name(")
        check(start >= 0) { "ReadBookActivity function not found: $name" }
        return bracedBody(source, start, name)
    }

    private fun activitySource(): String {
        return sourceFile("io/legado/app/ui/book/read/ReadBookActivity.kt")
    }

    private fun sourceFile(relativePath: String): String {
        val file = sequenceOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath")
        ).firstOrNull(File::isFile) ?: error("Source not found: $relativePath")
        return file.readText()
    }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        check(start >= 0) { "Function not found: $name" }
        return bracedBody(source, start, name)
    }

    private fun bracedBody(source: String, start: Int, name: String): String {
        val bodyStart = source.indexOf('{', start)
        check(bodyStart >= 0) { "Function body not found: $name" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(bodyStart + 1, index)
            }
        }
        error("Unterminated function: $name")
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val match = indexOf(value, offset)
            if (match < 0) return count
            count++
            offset = match + value.length
        }
    }
}
