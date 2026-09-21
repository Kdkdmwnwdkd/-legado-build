package io.legado.app.model.localBook

import io.legado.app.utils.HtmlFormatter
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements

internal object EpubTextContentFormatter {

    fun format(elements: Elements, removeRubyAnnotations: Boolean): String {
        elements.select("title,script,style,link,meta").remove()
        elements.select("[style*=display:none], [style*=display: none], [hidden]").remove()
        elements.select("img[src=\"cover.jpeg\"]").forEachIndexed { index, image ->
            if (index > 0) image.remove()
        }
        elements.select("img").forEach { image ->
            val src = image.attr("src")
            if (src.isBlank()) {
                image.remove()
                return@forEach
            }
            image.clearAttributes()
            image.attr("src", src)
        }
        if (removeRubyAnnotations) {
            elements.select("ruby").forEach { ruby ->
                ruby.select("rp,rt").remove()
                val textNode = TextNode(ruby.text())
                ruby.replaceWith(textNode)
                val previous = textNode.previousSibling()
                val next = textNode.nextSibling()
                when {
                    previous is TextNode && next is TextNode -> {
                        previous.text(previous.text() + textNode.text() + next.text())
                        textNode.remove()
                        next.remove()
                    }

                    previous is TextNode -> {
                        previous.text(previous.text() + textNode.text())
                        textNode.remove()
                    }

                    next is TextNode -> {
                        textNode.text(textNode.text() + next.text())
                        next.remove()
                    }
                }
            }
        }
        return HtmlFormatter.formatKeepImg(elements.outerHtml())
    }
}
