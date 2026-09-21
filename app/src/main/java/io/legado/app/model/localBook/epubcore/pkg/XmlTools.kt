package io.legado.app.model.localBook.epubcore.pkg

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource

internal object XmlTools {

    fun parse(bytes: ByteArray): Document {
        return factory().newDocumentBuilder().apply {
            // EPUB navigation files frequently carry the standard NCX/XHTML
            // DOCTYPE. Keep external entities disabled while allowing that
            // harmless declaration to be parsed; otherwise a valid package
            // fails before its cover or table of contents can be inspected.
            setEntityResolver(EntityResolver { _, _ ->
                InputSource(StringReader(""))
            })
        }.parse(ByteArrayInputStream(bytes))
    }

    private fun factory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
            setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
            setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttributeIfSupported("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttributeIfSupported("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
    }

    private fun DocumentBuilderFactory.setFeatureIfSupported(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun DocumentBuilderFactory.setAttributeIfSupported(name: String, value: Any) {
        runCatching { setAttribute(name, value) }
    }
}

internal fun Document.elements(localName: String): List<Element> {
    return documentElement.elements(localName)
}

internal fun Element.elements(localName: String): List<Element> {
    val result = ArrayList<Element>()
    fun visit(node: Node) {
        if (node is Element && node.matchesName(localName)) result.add(node)
        val children = node.childNodes
        for (i in 0 until children.length) visit(children.item(i))
    }
    visit(this)
    return result
}

internal fun Element.children(localName: String): List<Element> {
    val result = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val node = nodes.item(i)
        if (node is Element && node.matchesName(localName)) result.add(node)
    }
    return result
}

internal fun Element.attr(name: String): String? {
    if (hasAttribute(name)) return getAttribute(name)
    val local = name.substringAfter(':')
    return if (hasAttribute(local)) getAttribute(local) else null
}

internal fun Element.firstText(localName: String): String? {
    return elements(localName).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Element.matchesName(localName: String): Boolean {
    return this.localName == localName || nodeName == localName || nodeName.endsWith(":$localName")
}
