package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class EpubDirectPublisherCssTest {

    @Test
    fun `classification collects linked css without rewriting publisher xhtml`() {
        val source = "<html><head><link rel='stylesheet' href='../styles/main.css' media='screen'/></head><body><header>Title</header></body></html>"
        val collected = EpubDirectPublisherCss.collectForClassification(
            sourceHtml = source,
            chapterHref = "OPS/text/chapter.xhtml",
            resourceHost = "epub.local"
        ) { path, _ ->
            when (path) {
                "OPS/styles/main.css" -> "@import 'header.css'; header{position:absolute;top:0;left:0}".toByteArray()
                "OPS/styles/header.css" -> "header::before{content:'';display:block}".toByteArray()
                else -> null
            }
        }

        assertTrue(collected.contains("@media screen"))
        assertTrue(collected.contains("position:absolute"))
        assertTrue(collected.contains("header::before"))
        assertTrue(source.contains("<link rel='stylesheet'"))
    }

    @Test
    fun `classification ignores stylesheets that are inactive on screen`() {
        val requested = arrayListOf<String>()
        val source = """
            <html><head>
              <link rel='stylesheet' href='screen.css' media='screen'/>
              <link rel='stylesheet' href='print.css' media='print'/>
              <link rel='alternate stylesheet' href='alternate.css'/>
              <link rel='stylesheet' href='disabled.css' disabled='disabled'/>
            </head><body>Text</body></html>
        """.trimIndent()

        val collected = EpubDirectPublisherCss.collectForClassification(
            sourceHtml = source,
            chapterHref = "OPS/chapter.xhtml",
            resourceHost = "epub.local"
        ) { path, _ ->
            requested += path
            ".page{position:absolute;inset:0}".toByteArray()
        }

        assertTrue(collected.contains("@media screen"))
        assertTrue("requested=$requested", "OPS/screen.css" in requested)
        assertFalse("requested=$requested", "OPS/print.css" in requested)
        assertFalse("requested=$requested", "OPS/alternate.css" in requested)
        assertFalse("requested=$requested", "OPS/disabled.css" in requested)
    }

    @Test
    fun `classification ignores imports from inactive style elements`() {
        val requested = arrayListOf<String>()
        val source = """
            <html><head>
              <style media='screen'>@import 'screen.css';</style>
              <style media='print'>@import 'print.css';</style>
              <style media='tv'>@import 'tv.css';</style>
              <style disabled='disabled'>@import 'disabled.css';</style>
            </head><body>Text</body></html>
        """.trimIndent()

        val collected = EpubDirectPublisherCss.collectForClassification(
            sourceHtml = source,
            chapterHref = "OPS/chapter.xhtml",
            resourceHost = "epub.local"
        ) { path, _ ->
            requested += path
            ".page{position:absolute;inset:0}".toByteArray()
        }

        assertTrue(collected.contains("position:absolute"))
        assertTrue("requested=$requested", "OPS/screen.css" in requested)
        assertFalse("requested=$requested", "OPS/print.css" in requested)
        assertFalse("requested=$requested", "OPS/tv.css" in requested)
        assertFalse("requested=$requested", "OPS/disabled.css" in requested)
    }

    @Test
    fun `external stylesheets and imports are inlined with their own base path`() {
        val resources = mapOf(
            "OPS/styles/main.css" to """
                @import "nested/print.css" screen;
                p { duokan-text-indent: 2em; background: url('../images/paper.png'); }
                .page { duokan-bleed: auto; color: red; }
            """.trimIndent(),
            "OPS/styles/nested/print.css" to "span { duokan-text-indent: 1em; }"
        )

        val requested = arrayListOf<String>()
        val result = EpubDirectPublisherCss.inline(
            sourceHtml = "<html><head><link rel='stylesheet' href='../styles/main.css'/></head><body><p>Text</p></body></html>",
            chapterHref = "OPS/text/chapter.xhtml",
            resourceHost = "b123.epub.local"
        ) { path, _ ->
            requested += path
            resources[path]?.toByteArray()
        }

        assertFalse("$result\nrequested=$requested", result.contains("<link"))
        assertTrue(result.contains("@media screen"))
        assertTrue(result.contains("text-indent: 2em"))
        assertTrue(result.contains("text-indent: 1em"))
        assertFalse(result.contains("duokan-", ignoreCase = true))
        assertTrue(result.contains("https://b123.epub.local/OPS/images/paper.png"))
    }

    @Test
    fun `external and missing stylesheets remain linked`() {
        val source = """
            <html><head>
            <link rel="stylesheet" href="https://example.com/theme.css"/>
            <link rel="stylesheet" href="missing.css"/>
            </head><body>Text</body></html>
        """.trimIndent()

        val result = EpubDirectPublisherCss.inline(source, "OPS/chapter.xhtml", "epub.local") { _, _ -> null }

        assertTrue(result.contains("https://example.com/theme.css"))
        assertTrue(result.contains("missing.css"))
    }

    @Test
    fun `stylesheet loader failure does not fail the chapter`() {
        val source = "<html><head><link rel='stylesheet' href='broken.css'/></head><body>Text</body></html>"

        val result = EpubDirectPublisherCss.inline(source, "OPS/chapter.xhtml", "epub.local") { _, _ ->
            error("corrupt ZIP entry")
        }

        assertTrue(result.contains("broken.css"))
        assertTrue(result.contains("Text"))
    }

    @Test
    fun `document base and xml base are applied to linked stylesheets`() {
        val requested = arrayListOf<String>()
        val source = """
            <html>
              <head><base href="../shared/themes/"/></head>
              <body><div xml:base="dark/"><link rel="stylesheet" href="page.css"/></div></body>
            </html>
        """.trimIndent()

        val result = EpubDirectPublisherCss.inline(
            source,
            "OPS/text/chapter.xhtml",
            "epub.local"
        ) { path, _ ->
            requested += path
            if (path == "OPS/shared/themes/dark/page.css") {
                ".hero{background:url(../images/hero.png)}".toByteArray()
            } else {
                null
            }
        }

        assertTrue("requested=$requested", "OPS/shared/themes/dark/page.css" in requested)
        assertFalse(result.contains("<link"))
        assertTrue(result.contains("https://epub.local/OPS/shared/themes/images/hero.png"))
    }

    @Test
    fun `inline imports use the style element base and preserve media`() {
        val source = """
            <html><head><base href="../assets/"/></head><body>
              <style xml:base="themes/">@import "print.css" print;.local{background:url(local.png)}</style>
            </body></html>
        """.trimIndent()

        val result = EpubDirectPublisherCss.inline(
            source,
            "OPS/text/chapter.xhtml",
            "epub.local"
        ) { path, _ ->
            if (path == "OPS/assets/themes/print.css") {
                ".print{background:url(../images/paper.png)}".toByteArray()
            } else {
                null
            }
        }

        assertTrue(result.contains("@media print"))
        assertTrue(result.contains("https://epub.local/OPS/assets/images/paper.png"))
        assertTrue(result.contains("url(local.png)"))
    }

    @Test
    fun `linked stylesheet honors charset before it is inlined`() {
        val stylesheet = (
            "@charset \"ISO-8859-1\";\n" +
                ".caption::before { content: \"caf\u00e9\"; position: absolute; }"
            ).toByteArray(StandardCharsets.ISO_8859_1)

        val result = EpubDirectPublisherCss.inline(
            "<html><head><link rel='stylesheet' href='legacy.css'/></head><body/></html>",
            "OPS/chapter.xhtml",
            "epub.local"
        ) { path, _ ->
            stylesheet.takeIf { path == "OPS/legacy.css" }
        }

        assertTrue(result.contains("caf\u00e9"))
        assertFalse(result.contains("\uFFFD"))
        assertFalse(result.contains("@charset", ignoreCase = true))
    }

    @Test
    fun `classification honors a utf16 byte order mark`() {
        val source = ".page { position: absolute; font-family: '\u5b8b\u4f53'; }"
        val encoded = byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
            source.toByteArray(StandardCharsets.UTF_16LE)

        val collected = EpubDirectPublisherCss.collectForClassification(
            sourceHtml = "<html><head><link rel='stylesheet' href='utf16.css'/></head><body/></html>",
            chapterHref = "OPS/chapter.xhtml",
            resourceHost = "epub.local"
        ) { path, _ ->
            encoded.takeIf { path == "OPS/utf16.css" }
        }

        assertTrue(collected.contains("position: absolute"))
        assertTrue(collected.contains("\u5b8b\u4f53"))
        assertFalse(collected.contains("\u0000"))
    }

    @Test
    fun `normalizer changes declarations without changing similarly named properties`() {
        val result = EpubDirectPublisherCss.normalize(
            ".a{x-duokan-text-indent:4em;duokan-text-indent:2em;duokan-bleed:crop;color:red}"
        )

        assertTrue(result.contains("x-duokan-text-indent:4em"))
        assertTrue(result.contains("text-indent:2em"))
        assertFalse(result.contains("duokan-bleed"))
        assertTrue(result.contains("color:red"))
    }
}
