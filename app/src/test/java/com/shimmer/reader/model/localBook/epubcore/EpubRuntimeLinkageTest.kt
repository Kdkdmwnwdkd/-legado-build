package com.shimmer.reader.model.localBook.epubcore

import org.junit.Test

class EpubRuntimeLinkageTest {

    @Test
    fun `critical direct runtime classes initialize together`() {
        val loader = javaClass.classLoader
        listOf(
            "com.shimmer.reader.model.localBook.epubcore.pkg.EpubPackageParser",
            "com.shimmer.reader.model.localBook.epubcore.toc.EpubTocParser",
            "com.shimmer.reader.model.localBook.epubcore.font.EpubFontDeobfuscatingArchive",
            "com.shimmer.reader.model.localBook.epubcore.direct.EpubDirectSession",
            "com.shimmer.reader.model.localBook.epubcore.direct.EpubDirectCssMediaPolicy",
            "com.shimmer.reader.model.localBook.epubcore.direct.EpubDirectContentClassifier",
            "com.shimmer.reader.model.localBook.epubcore.direct.EpubDirectPublisherCss",
            "com.shimmer.reader.model.localBook.epubcore.direct.EpubDirectDocumentBuilder",
            "com.shimmer.reader.model.localBook.epubcore.direct.EpubDirectRangePolicy",
            "com.shimmer.reader.model.localBook.epubcore.facade.EpubCoreFacade",
            "com.shimmer.reader.model.localBook.epubcore.facade.EpubCoreProvider",
            "com.shimmer.reader.model.localBook.epubcore.web.EpubWebDocumentLoadMarker",
            "com.shimmer.reader.ui.book.read.epub.EpubDirectWebLayer",
            "com.shimmer.reader.ui.book.read.epub.EpubReadView"
        ).forEach { className ->
            Class.forName(className, true, loader)
        }
    }
}
