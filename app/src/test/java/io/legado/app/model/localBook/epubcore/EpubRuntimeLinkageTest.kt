package io.legado.app.model.localBook.epubcore

import org.junit.Test

class EpubRuntimeLinkageTest {

    @Test
    fun `critical direct runtime classes initialize together`() {
        val loader = javaClass.classLoader
        listOf(
            "io.legado.app.model.localBook.epubcore.pkg.EpubPackageParser",
            "io.legado.app.model.localBook.epubcore.toc.EpubTocParser",
            "io.legado.app.model.localBook.epubcore.font.EpubFontDeobfuscatingArchive",
            "io.legado.app.model.localBook.epubcore.direct.EpubDirectSession",
            "io.legado.app.model.localBook.epubcore.direct.EpubDirectCssMediaPolicy",
            "io.legado.app.model.localBook.epubcore.direct.EpubDirectContentClassifier",
            "io.legado.app.model.localBook.epubcore.direct.EpubDirectPublisherCss",
            "io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder",
            "io.legado.app.model.localBook.epubcore.direct.EpubDirectRangePolicy",
            "io.legado.app.model.localBook.epubcore.facade.EpubCoreFacade",
            "io.legado.app.model.localBook.epubcore.facade.EpubCoreProvider",
            "io.legado.app.model.localBook.epubcore.web.EpubWebDocumentLoadMarker",
            "io.legado.app.ui.book.read.epub.EpubDirectWebLayer",
            "io.legado.app.ui.book.read.epub.EpubReadView"
        ).forEach { className ->
            Class.forName(className, true, loader)
        }
    }
}
