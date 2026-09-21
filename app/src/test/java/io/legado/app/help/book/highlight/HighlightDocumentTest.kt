package io.legado.app.help.book.highlight

import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.junit.Assert.*
import org.junit.Test

class HighlightDocumentTest {
    private fun render(html: String, vararg rules: HighlightRule): String =
        HighlightDocument.apply(html, rules.toList()) { "https://book.epub.local/highlight-asset/" + it }

    private fun words(node: Node): String = if (node is TextNode) node.wholeText else
        node.childNodes().joinToString("") { words(it) }

    @Test fun preservesExactTextAndOriginalBlockOffsets() {
        val html = """<p data-reader-block="block-2" data-legado-text-offset="17">前  「甲&amp;<b>乙</b>」 😀 后</p>"""
        val result = Jsoup.parse(render(html, HighlightRule(keyword = "「[^」]*」", isRegex = true)))
        assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
        assertEquals("17", result.selectFirst("p")!!.attr("data-legado-text-offset"))
        assertEquals("block-2", result.selectFirst("p")!!.attr("data-reader-block"))
    }

    @Test fun oneBubbleWrapsNestedInlineMarkup() {
        val result = Jsoup.parse(render("<p>前「<b>强调</b>文字」后</p>",
            HighlightRule(keyword = "「[^」]*」", isRegex = true, asset = "image")))
        val bubble = result.select("[data-legado-highlight]").single()
        assertEquals("「强调文字」", bubble.text())
        assertEquals("强调", bubble.select("b").text())
        assertEquals(1, result.select("style").size)
        assertEquals(1, Regex("https://book.epub.local/highlight-asset/image").findAll(result.outerHtml()).count())
    }

    @Test fun splittingMarkupRetainsLinksAndUniqueFragmentIds() {
        val html = """<p><a id="fragment" href="next.xhtml">前命中后</a></p>"""
        val result = Jsoup.parse(render(html, HighlightRule(keyword = "命中")))
        assertEquals(1, result.select("#fragment").size)
        assertEquals(3, result.select("a[href=next.xhtml]").size)
        assertEquals("前命中后", result.select("p").text())
    }

    @Test fun inlineImagesAndTheirClickActionsRemainExactlyOnce() {
        val html = """<p>前<img src="original.png" data-legado-image-id="image-1"><a href="#__legado_source_image_x" data-legado-image-action="x"><img src="bubble.png"></a>命中后<img src="last.png"></p>"""
        val result = Jsoup.parse(render(html, HighlightRule(keyword = "命中")))
        assertEquals(listOf("original.png", "bubble.png", "last.png"), result.select("img").map { it.attr("src") })
        assertEquals(1, result.select("[data-legado-image-action=x]").size)
    }

    @Test fun emptyTitleRuleDecoratesOnlyTheTitleAsOneBlock() {
        val result = Jsoup.parse(render("<h2>第<b>一</b>章</h2><p>正文</p>",
            HighlightRule(keyword = "", titleOnly = true, asset = "image")))
        assertTrue(result.selectFirst("h2")!!.hasClass("legado-highlight-0"))
        assertFalse(result.selectFirst("p")!!.hasClass("legado-highlight-0"))
        assertEquals(0, result.select("[data-legado-highlight]").size)
    }

    @Test fun crossParagraphRegexKeepsBothParagraphsAndAllTheirWords() {
        val html = "<p>开头</p><p>简介：第一行</p><p>第二行</p><p>结束</p>"
        val result = Jsoup.parse(render(html, HighlightRule(keyword = "简介：[\\s\\S]*?(?=\\n结束)", isRegex = true)))
        assertEquals(4, result.select("p").size)
        assertEquals(2, result.select("p.legado-highlight-0").size)
        assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
    }

    @Test fun mixedBodyAndNestedBlocksAreBothMatched() {
        val result = Jsoup.parse(render("命中<div><p>命中</p></div>尾命中",
            HighlightRule(keyword = "命中")))
        assertTrue(result.body().html().startsWith("<span"))
        assertTrue(result.selectFirst("p")!!.hasClass("legado-highlight-0"))
        assertTrue(result.body().html().endsWith("命中</span>"))
    }

    @Test fun stylePrecedenceFollowsRuleOrderInsteadOfFirstMatchedParagraph() {
        val first = HighlightRule(keyword = "后", styleColorType = "blue")
        val second = HighlightRule(keyword = "先", styleColorType = "red")
        val html = render("<p>先</p><p>后</p>", first, second)
        val style = Jsoup.parse(html).getElementById("legado-reeden-highlight-style")!!.html()
        assertTrue(style.indexOf(".legado-highlight-0") < style.indexOf(".legado-highlight-1"))
    }

    @Test fun overlappingRangesComposeOnSameSpanWithoutDuplicatingWords() {
        val result = Jsoup.parse(render("<p>abcdef</p>", HighlightRule(keyword = "abc"), HighlightRule(keyword = "bcde")))
        assertEquals("abcdef", result.selectFirst("p")!!.text())
        val overlap = result.select(".legado-highlight-0.legado-highlight-1").single()
        assertEquals("bc", overlap.text())
    }

    @Test fun scriptsStylesAndRubyAnnotationsAreNotHighlightTargets() {
        val html = "<p>注<ruby>正文<rt>命中</rt></ruby></p><script>var x='命中'</script><style>/*命中*/</style>"
        assertEquals(html, render(html, HighlightRule(keyword = "命中")))
    }

    @Test fun noMatchAndDisabledRulesReturnTheOriginalDocument() {
        val html = "<p class='original'>正文</p>"
        assertEquals(html, render(html, HighlightRule(keyword = "无")))
        assertEquals(html, render(html, HighlightRule(keyword = "正文", enabled = false)))
    }

    @Test fun applyingAnAlreadyPreparedDocumentDoesNotNestDecorations() {
        val rule = HighlightRule(keyword = "命中")
        val once = render("<p>前命中后</p>", rule)
        assertEquals(once, render(once, rule))
    }

    @Test fun inlineBubblesCannotTurnConsecutiveParagraphsIntoOneLine() {
        val rule = HighlightRule(keyword = "“[^”]+”", isRegex = true, styleCssText = "display:inline;padding:4px;")
        val result = Jsoup.parse(render("<p>“第一段”</p><p>“第二段”</p>", rule))
        assertEquals(2, result.select("p > span.legado-highlight-0").size)
        assertFalse(result.select("p").any { it.hasClass("legado-highlight-0") })
    }

    @Test fun inlineArtworkFlowKeepsParagraphOffsetsLinksImagesAndAllLongQuoteText() {
        val html = """<p data-reader-block="block-2" data-legado-text-offset="17">前“<b>强调</b><a id="note" href="#n">链接</a><img src="bubble.png">${"长对话🌅".repeat(1000)}”后</p><p>下一段</p>"""
        val rule = HighlightRule(keyword = "“[^”]+”", isRegex = true, asset = "lotus",
            styleCssText = "display:inline;padding:12px 16px;")
        val result = Jsoup.parse(render(html, rule))
        assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
        assertEquals(2, result.select("p").size)
        assertEquals("17", result.selectFirst("p")!!.attr("data-legado-text-offset"))
        assertEquals(1, result.select("p > [data-legado-highlight-flow]").size)
        assertEquals(1, result.select("[data-legado-highlight]").size)
        assertEquals(1, result.select("#note[href=#n]").size)
        assertEquals(1, result.select("img[src=bubble.png]").size)
        assertFalse(result.select("style").html().contains("display:inline-block"))
        assertEquals("下一段", result.select("p")[1].text())
    }

    @Test fun paragraphEdgesDoNotAddAnIndentButInternalQuotesKeepTheirSeparation() {
        val html = "<p>“段首”后文</p><p>前文“段尾”</p><p>“完整<b>引语</b>”</p>" +
            "<p>前文“中间”后文</p><p> \n“带空白” \t</p>"
        val rule = HighlightRule(keyword = "“[^”]+”", isRegex = true, asset = "rabbit",
            styleCssText = "display:inline;padding:9px 16px;")
        val result = Jsoup.parse(render(html, rule))
        val spacing = result.select("[data-legado-highlight-spacing]")
        assertEquals(5, spacing.size)
        assertEquals("margin-left:0!important;", spacing[0].attr("style"))
        assertEquals("margin-right:0!important;", spacing[1].attr("style"))
        assertEquals("margin-left:0!important;margin-right:0!important;", spacing[2].attr("style"))
        assertFalse(spacing[3].hasAttr("style"))
        assertEquals(spacing[2].attr("style"), spacing[4].attr("style"))
        assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
    }

    @Test fun artworkGapsRespectPunctuationWhitespaceAndNonbreakingBoundaries() {
        val rule = HighlightRule(keyword = "标记", asset = "rabbit",
            styleCssText = "display:inline;padding:9px 16px;")
        val prefixes = listOf("前：" to true, "前，" to true, "前；" to true, "前。" to true,
            "前" to true, "前 \t" to true, "前（" to false, "前【" to false,
            "前「" to false, "前“" to false, "前\u00a0" to false, "前\u2060" to false,
            "前\uFEFF" to false, "前\u200D" to false, "前\u202F" to false, "前\u2011" to false)
        prefixes.forEach { (prefix, breakable) ->
            val html = "<p>${prefix}标记后</p>"
            val result = Jsoup.parse(render(html, rule))
            assertEquals(prefix, breakable, result.select("[data-legado-highlight-gap-before]").isNotEmpty())
            assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
        }
        val suffixes = listOf("后" to true, " 后" to true, "，后" to false, "。后" to false,
            "；后" to false, "：后" to false, "！后" to false, "？后" to false,
            "）后" to false, "】后" to false, "」后" to false, "”后" to false, "\u00a0后" to false,
            "\u2060后" to false, "\uFEFF后" to false, "\u200D后" to false, "\u202F后" to false)
        suffixes.forEach { (suffix, breakable) ->
            val html = "<p>前标记${suffix}</p>"
            val result = Jsoup.parse(render(html, rule))
            assertEquals(suffix, breakable, result.select("[data-legado-highlight-gap-after]").isNotEmpty())
            assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
        }
        val insideWord = Jsoup.parse(render("<p>highlight</p>", rule.copy(keyword = "hl")))
        assertTrue(insideWord.select("[data-legado-highlight-gap]").isEmpty())
    }

    @Test fun collapsibleArtworkGapsDoNotAddSourceCharactersOrDuplicateInlineObjects() {
        val html = """<p data-legado-text-offset="34">他说：<a id="quote" href="#note">“<b>高亮</b><img src="note.png">文字”</a> 然后“继续”。</p>"""
        val rule = HighlightRule(keyword = "“[^”]+”", isRegex = true, asset = "rabbit",
            styleCssText = "display:inline;padding:.4em .8em;")
        val result = Jsoup.parse(render(html, rule))
        assertTrue(result.select("[data-legado-highlight-gap]").isNotEmpty())
        assertTrue(result.select("[data-legado-highlight-gap]").all { it.childNodeSize() == 0 && it.attr("aria-hidden") == "true" })
        assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
        assertEquals("34", result.selectFirst("p")!!.attr("data-legado-text-offset"))
        assertEquals(1, result.select("#quote[href=#note]").size)
        assertEquals(1, result.select("img[src=note.png]").size)
        assertEquals("高亮", result.select("b").text())
        assertEquals(2, result.select("[data-legado-highlight]").size)
    }

    @Test fun sourceWhitespaceOwnsBothAdjacentArtworkGapsWithoutLosingMarkupOrBreaks() {
        val html = """<p>前<a id="space" href="#note"> &#9;</a>“第一段” <b> </b>“第二段” 后<br>“第三段”</p>"""
        val rule = HighlightRule(keyword = "“[^”]+”", isRegex = true, asset = "rabbit",
            styleCssText = "display:inline;padding:9px 16px;")
        val result = Jsoup.parse(render(html, rule))
        assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
        assertEquals(1, result.select("#space[href=#note]").size)
        assertEquals(1, result.select("b").size)
        assertEquals(1, result.select("br").size)
        assertTrue(result.select("[data-legado-highlight-source-gap]").any {
            it.hasClass("legado-highlight-0-gap-before") && it.hasClass("legado-highlight-0-gap-after")
        })
        assertTrue(result.select("[data-legado-highlight-gap=before]").isEmpty())
        assertTrue(result.select("[data-legado-highlight-gap=after]").isEmpty())
        assertEquals(3, result.select("[data-legado-highlight]").size)
    }

    @Test fun closingQuoteArtworkGapsAllowCjkTextAndPreserveFollowingPunctuation() {
        val rule = HighlightRule(keyword = "“标记”", asset = "rabbit",
            styleCssText = "display:inline;padding:9px 16px;")
        val suffixes = listOf("后文" to true, "かな" to true, "カナ" to true, "한글" to true,
            "，后文" to false, "。后文" to false, "）后文" to false, "”后文" to false,
            "\u00a0后文" to false, "\u2060后文" to false, "\u200D后文" to false)
        suffixes.forEach { (suffix, breakable) ->
            val html = "<p>前：“标记”${suffix}</p>"
            val result = Jsoup.parse(render(html, rule))
            assertEquals(suffix, breakable, result.select("[data-legado-highlight-gap-after]").isNotEmpty())
            assertEquals(words(Jsoup.parse(html).body()), words(result.body()))
        }
    }
}
