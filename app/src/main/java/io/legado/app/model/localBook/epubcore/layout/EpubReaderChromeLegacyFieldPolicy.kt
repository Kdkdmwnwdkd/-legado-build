package io.legado.app.model.localBook.epubcore.layout

/** Maps the persisted classic reader-tip integer contract to typed EPUB fields. */
object EpubReaderChromeLegacyFieldPolicy {

    fun resolve(value: Int): EpubReaderChromeField {
        return when (value) {
            1 -> EpubReaderChromeField.CHAPTER_TITLE
            2 -> EpubReaderChromeField.TIME
            3 -> EpubReaderChromeField.BATTERY
            4 -> EpubReaderChromeField.PAGE
            5 -> EpubReaderChromeField.TOTAL_PROGRESS
            6 -> EpubReaderChromeField.PAGE_AND_TOTAL
            7 -> EpubReaderChromeField.BOOK_NAME
            8 -> EpubReaderChromeField.TIME_BATTERY
            9 -> EpubReaderChromeField.TIME_BATTERY_PERCENTAGE
            10 -> EpubReaderChromeField.BATTERY_PERCENTAGE
            11 -> EpubReaderChromeField.CHAPTER_PROGRESS
            else -> EpubReaderChromeField.NONE
        }
    }
}
