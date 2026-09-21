package io.legado.app.ui.book.read.epub

import android.content.Context
import android.os.Looper
import io.legado.app.constant.AppLog
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectPosition
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubReaderChromeData
import java.io.Closeable

internal class EpubAdjacentPageFramePipeline(
    context: Context,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    densityDpi: Int,
    private val farPrefetchEnabled: Boolean = true,
    cacheCapacity: Int = DEFAULT_CACHE_CAPACITY
) : Closeable {

    enum class Direction {
        Previous,
        Next
    }

    private enum class Distance {
        Near,
        Far
    }

    interface Listener {
        fun onAdjacentFrameReady(direction: Direction, target: EpubPageFrameTarget) = Unit
    }

    private data class SlotKey(
        val direction: Direction,
        val distance: Distance
    )

    private data class RenderSlot(
        val key: SlotKey,
        val renderer: EpubVirtualPageRenderer,
        var target: EpubPageFrameTarget? = null
    )

    private val frameCache = EpubDirectPreloadCache<EpubRenderedPageFrame>(
        cacheCapacity,
        EpubRenderedPageFrame::close
    )
    private val slots: Map<SlotKey, RenderSlot>
    private val preparedChapters = LinkedHashMap<Int, EpubDirectChapter>()
    private val desiredTargets = mutableMapOf<Direction, EpubPageFrameTarget?>()
    private val prefetchTargets = mutableMapOf<Direction, EpubPageFrameTarget?>()
    private var listener: Listener? = null
    private var session: EpubDirectSession? = null
    private var sessionGeneration = 0L
    private var config: EpubCoreLayoutConfig? = null
    private var readerChromeData = EpubReaderChromeData()
    private var layoutSignature: String? = null
    private var currentChapter: EpubDirectChapter? = null
    private var currentPosition: EpubDirectPosition? = null
    private var schedulingSuspended = false
    private var closed = false

    init {
        checkMainThread()
        require(viewportWidth > 0 && viewportHeight > 0)
        val createdSlots = mutableListOf<RenderSlot>()
        slots = try {
            val distances = if (farPrefetchEnabled) {
                Distance.values().asList()
            } else {
                listOf(Distance.Near)
            }
            Direction.values().forEach { direction ->
                distances.forEach { distance ->
                    createdSlots += RenderSlot(
                        key = SlotKey(direction, distance),
                        renderer = EpubVirtualPageRenderer(
                            context,
                            viewportWidth,
                            viewportHeight,
                            densityDpi
                        )
                    )
                }
            }
            createdSlots.associateBy(RenderSlot::key)
        } catch (throwable: Throwable) {
            createdSlots.forEach { it.renderer.close() }
            throw throwable
        }
    }

    fun setListener(value: Listener?) {
        checkMainThread()
        listener = value
    }

    fun bindCurrent(
        session: EpubDirectSession,
        chapter: EpubDirectChapter,
        config: EpubCoreLayoutConfig,
        position: EpubDirectPosition,
        readerChromeData: EpubReaderChromeData = EpubReaderChromeData()
    ) {
        checkMainThread()
        if (closed) return
        require(position.chapterIndex == chapter.chapterIndex)
        val nextLayoutSignature = EpubPageFrameTarget.layoutSignature(
            config,
            viewportWidth,
            viewportHeight
        )
        val readerChromeContentChanged = config.readerChrome.enabled &&
            this.readerChromeData.contentRevision != readerChromeData.contentRevision
        if (this.session !== session || layoutSignature != nextLayoutSignature) {
            sessionGeneration++
            frameCache.clear()
            preparedChapters.clear()
            cancelSlots()
        } else if (readerChromeContentChanged) {
            sessionGeneration++
            frameCache.clear()
            cancelSlots()
        }
        this.session = session
        this.config = config
        this.readerChromeData = readerChromeData
        layoutSignature = nextLayoutSignature
        currentChapter = chapter
        currentPosition = position
        preparedChapters[chapter.chapterIndex] = chapter
        prunePreparedChapters(session, chapter.chapterIndex)
        scheduleDesiredTargets()
    }

    fun offerPreparedChapter(
        session: EpubDirectSession,
        chapter: EpubDirectChapter,
        config: EpubCoreLayoutConfig
    ) {
        checkMainThread()
        if (closed || this.session !== session) return
        val offeredSignature = EpubPageFrameTarget.layoutSignature(
            config,
            viewportWidth,
            viewportHeight
        )
        if (offeredSignature != layoutSignature) return
        val currentIndex = currentChapter?.chapterIndex ?: return
        val adjacentIndexes = setOfNotNull(
            session.adjacentChapterIndex(currentIndex, -1),
            session.adjacentChapterIndex(currentIndex, 1)
        )
        if (chapter.chapterIndex !in adjacentIndexes) return
        preparedChapters[chapter.chapterIndex] = chapter
        scheduleDesiredTargets()
    }

    fun hasFrame(direction: Direction): Boolean {
        checkMainThread()
        val target = desiredTargets[direction] ?: return false
        return frameCache.contains(target.cacheKey)
    }

    fun takeFrame(direction: Direction): EpubRenderedPageFrame? {
        checkMainThread()
        val target = desiredTargets[direction] ?: return null
        val frame = frameCache.take(target.cacheKey) ?: return null
        return frame.takeIf(target::accepts) ?: run {
            frame.close()
            null
        }
    }

    fun suspendScheduling() {
        checkMainThread()
        if (closed) return
        schedulingSuspended = true
    }

    fun resumeScheduling() {
        checkMainThread()
        if (closed) return
        schedulingSuspended = false
        scheduleDesiredTargets()
    }

    fun offerFrame(frame: EpubRenderedPageFrame): Boolean {
        checkMainThread()
        if (closed || frame.bitmap.isRecycled) {
            frame.close()
            return false
        }
        val target = Direction.values().asSequence()
            .mapNotNull(desiredTargets::get)
            .plus(Direction.values().asSequence().mapNotNull(prefetchTargets::get))
            .firstOrNull { it.accepts(frame) }
            ?: run {
                frame.close()
                return false
            }
        slots.values.forEach { slot ->
            if (slot.target == target) cancelSlot(slot)
        }
        frameCache.put(target.cacheKey, frame)
        Direction.values().forEach { direction ->
            if (desiredTargets[direction] == target) {
                listener?.onAdjacentFrameReady(direction, target)
            }
        }
        scheduleDesiredTargets()
        return true
    }

    fun target(direction: Direction): EpubPageFrameTarget? {
        checkMainThread()
        return desiredTargets[direction]
    }

    fun clearFrames() {
        checkMainThread()
        frameCache.clear()
        cancelSlots()
        desiredTargets.clear()
        prefetchTargets.clear()
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        listener = null
        frameCache.clear()
        desiredTargets.clear()
        prefetchTargets.clear()
        preparedChapters.clear()
        slots.values.forEach { slot ->
            slot.target = null
            slot.renderer.close()
        }
        session = null
        config = null
        currentChapter = null
        currentPosition = null
    }

    private fun scheduleDesiredTargets() {
        val activeSession = session ?: return
        val activeChapter = currentChapter ?: return
        val activeConfig = config ?: return
        val position = currentPosition ?: return
        val previousChapterIndex = activeSession.adjacentChapterIndex(position.chapterIndex, -1)
            ?.takeIf(preparedChapters::containsKey)
        val nextChapterIndex = activeSession.adjacentChapterIndex(position.chapterIndex, 1)
            ?.takeIf(preparedChapters::containsKey)
        val plan = EpubAdjacentPageTargetPolicy.plan(
            chapterIndex = position.chapterIndex,
            pageIndex = position.pageIndex,
            pageCount = position.pageCount,
            previousChapterIndex = previousChapterIndex,
            nextChapterIndex = nextChapterIndex
        )
        scheduleDirection(
            direction = Direction.Previous,
            request = plan.previous,
            prefetchRequest = plan.previousPrefetch,
            session = activeSession,
            config = activeConfig
        )
        scheduleDirection(
            direction = Direction.Next,
            request = plan.next,
            prefetchRequest = plan.nextPrefetch,
            session = activeSession,
            config = activeConfig
        )
        preparedChapters[activeChapter.chapterIndex] = activeChapter
    }

    private fun scheduleDirection(
        direction: Direction,
        request: EpubAdjacentPageRequest?,
        prefetchRequest: EpubAdjacentPageRequest?,
        session: EpubDirectSession,
        config: EpubCoreLayoutConfig
    ) {
        fun targetFor(request: EpubAdjacentPageRequest?): EpubPageFrameTarget? {
            val chapter = request?.let { preparedChapters[it.chapterIndex] } ?: return null
            return EpubPageFrameTarget.create(
                sessionGeneration = sessionGeneration.coerceAtLeast(1L),
                chapter = chapter,
                config = config,
                request = request,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                readerChromeData = readerChromeData
            )
        }

        val desiredTarget = targetFor(request)
        val prefetchTarget = if (farPrefetchEnabled) targetFor(prefetchRequest) else null
        desiredTargets[direction] = desiredTarget
        prefetchTargets[direction] = prefetchTarget
        if (desiredTarget?.let { frameCache.contains(it.cacheKey) } == true) {
            listener?.onAdjacentFrameReady(direction, desiredTarget)
        }
        if (schedulingSuspended) {
            cancelMismatchedSlot(
                slot = checkNotNull(slots[SlotKey(direction, Distance.Near)]),
                target = desiredTarget
            )
            slots[SlotKey(direction, Distance.Far)]?.let { slot ->
                cancelMismatchedSlot(slot = slot, target = prefetchTarget)
            }
            return
        }
        schedule(
            slot = checkNotNull(slots[SlotKey(direction, Distance.Near)]),
            target = desiredTarget,
            session = session,
            config = config
        )
        slots[SlotKey(direction, Distance.Far)]?.let { slot ->
            schedule(
                slot = slot,
                target = prefetchTarget,
                session = session,
                config = config
            )
        }
    }

    private fun schedule(
        slot: RenderSlot,
        target: EpubPageFrameTarget?,
        session: EpubDirectSession,
        config: EpubCoreLayoutConfig
    ) {
        if (target == null || frameCache.contains(target.cacheKey)) {
            if (slot.target != null) cancelSlot(slot)
            return
        }
        if (slot.target == target) return
        val targetChapter = preparedChapters[target.chapterIndex] ?: return
        slot.target = target
        slot.renderer.render(
            session = session,
            chapter = targetChapter,
            config = config,
            pageIndex = target.requestedPageIndex,
            openAtEnd = target.openAtEnd,
            readerChromeData = readerChromeData
        ) { result ->
            val stillWanted = when (slot.key.distance) {
                Distance.Near -> desiredTargets[slot.key.direction] == target
                Distance.Far -> prefetchTargets[slot.key.direction] == target
            }
            if (closed || slot.target != target || !stillWanted) {
                result.getOrNull()?.close()
                return@render
            }
            slot.target = null
            val frame = result.getOrElse { failure ->
                AppLog.putDebug(
                    "EPUB adjacent frame render failed: direction=${slot.key.direction}, " +
                        "distance=${slot.key.distance}, " +
                        "chapter=${target.chapterIndex}, page=${target.requestedPageIndex}, " +
                        "openAtEnd=${target.openAtEnd}",
                    failure
                )
                return@render
            }
            if (!target.accepts(frame)) {
                frame.close()
                AppLog.putDebug(
                    "EPUB adjacent frame rejected: direction=${slot.key.direction}, " +
                        "distance=${slot.key.distance}, " +
                        "target=$target, actual=${frame.chapterIndex}/${frame.pageIndex}/${frame.pageCount}"
                )
                return@render
            }
            frameCache.put(target.cacheKey, frame)
            if (desiredTargets[slot.key.direction] == target) {
                listener?.onAdjacentFrameReady(slot.key.direction, target)
            }
            scheduleDesiredTargets()
        }
    }

    private fun cancelSlot(slot: RenderSlot) {
        if (slot.target == null) return
        slot.target = null
        slot.renderer.cancel()
    }

    private fun cancelMismatchedSlot(slot: RenderSlot, target: EpubPageFrameTarget?) {
        if (slot.target != null && slot.target != target) cancelSlot(slot)
    }

    private fun cancelSlots() {
        slots.values.forEach { slot ->
            if (slot.target != null) cancelSlot(slot)
        }
        desiredTargets.clear()
        prefetchTargets.clear()
    }

    private fun prunePreparedChapters(session: EpubDirectSession, currentChapterIndex: Int) {
        val retained = setOfNotNull(
            currentChapterIndex,
            session.adjacentChapterIndex(currentChapterIndex, -1),
            session.adjacentChapterIndex(currentChapterIndex, 1)
        )
        val iterator = preparedChapters.entries.iterator()
        while (iterator.hasNext()) {
            val index = iterator.next().key
            if (index !in retained) iterator.remove()
        }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "EPUB adjacent frame pipeline operation must run on the main thread"
        }
    }

    private companion object {
        const val DEFAULT_CACHE_CAPACITY = 8
    }
}
