package com.shimmer.reader.ui.book.read.epub

enum class EpubPageTurnResult {
    MovedWithinChapter,
    BoundaryRequired,
    Queued,
    Rejected,
    Unavailable;

    val requiresBoundaryNavigation: Boolean
        get() = this == BoundaryRequired
}
