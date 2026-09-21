package io.legado.app.model.localBook.epubcore.layout

/** Page-specific values rendered into the fixed-size reader chrome nodes. */
data class EpubReaderChromeData(
    val bookName: String = "",
    val chapterTitle: String = "",
    val pageLabel: String = "",
    val progressLabel: String = "",
    val chapterProgressLabel: String = "",
    val timeLabel: String = "",
    val batteryLabel: String = "",
    val batteryPercentageLabel: String = "",
    val headerLeft: String = "",
    val headerCenter: String = "",
    val headerRight: String = "",
    val footerLeft: String = "",
    val footerCenter: String = "",
    val footerRight: String = "",
    val chapterCount: Int = 0,
    val chapterFirstPage: Boolean = false,
    val contentRevision: Long = 0L
)
