package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EpubDirectReaderTypographyContractTest {

    @Test
    fun `runtime marks semantic prose without trusting publisher indent`() {
        val runtime = source("src/main/assets/epub/direct-runtime.js")

        assertTrue("var readerTypographyEnabled=" in runtime)
        assertTrue("function markReaderParagraphs()" in runtime)
        assertTrue("hasComplexParagraphLayout(paragraph)" in runtime)
        assertTrue("hasBackgroundArtwork(document.documentElement)" in runtime)
        assertTrue("paragraph.setAttribute('data-legado-reader-paragraph','true')" in runtime)
        assertTrue("markReaderParagraphs();\n    resourcesReady=true" in runtime)
        assertFalse("computed.textIndent" in runtime)
        assertFalse("inlineStyle" in runtime)
    }

    @Test
    fun `native runtime gate supplies the typography capability`() {
        val webLayer = source("src/main/java/io/legado/app/ui/book/read/epub/EpubDirectWebLayer.kt")

        assertTrue("EpubDirectReaderTypographyPolicy.isSupported" in webLayer)
        assertTrue("\"__LEGADO_READER_TYPOGRAPHY__\" to readerTypography.toString()" in webLayer)
    }

    private fun source(path: String): String {
        return sequenceOf(File("app/$path"), File(path))
            .firstOrNull(File::isFile)
            ?.readText()?.replace("\r\n", "\n")
            ?: error("Missing source: $path")
    }
}
