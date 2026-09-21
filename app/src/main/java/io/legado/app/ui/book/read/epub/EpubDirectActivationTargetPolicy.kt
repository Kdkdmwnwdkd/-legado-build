package io.legado.app.ui.book.read.epub

internal object EpubDirectActivationTargetPolicy {

    enum class Kind {
        PageIndex,
        ChapterStart,
        ChapterEnd
    }

    data class Target(
        val kind: Kind,
        val pageIndex: Int = 0
    ) {
        val jsBoundary: String?
            get() = when (kind) {
                Kind.ChapterStart -> "start"
                Kind.ChapterEnd -> "end"
                Kind.PageIndex -> null
            }
    }

    fun pageIndex(index: Int): Target = Target(Kind.PageIndex, index.coerceAtLeast(0))

    fun chapterBoundary(openAtEnd: Boolean): Target = Target(
        kind = if (openAtEnd) Kind.ChapterEnd else Kind.ChapterStart
    )

    fun resolve(target: Target, pageCount: Int): Int {
        val count = pageCount.coerceAtLeast(1)
        return when (target.kind) {
            Kind.PageIndex -> target.pageIndex.coerceIn(0, count - 1)
            Kind.ChapterStart -> 0
            Kind.ChapterEnd -> count - 1
        }
    }

    fun isSatisfied(
        target: Target,
        pageIndex: Int,
        pageCount: Int
    ): Boolean {
        return pageIndex == resolve(target, pageCount)
    }
}
