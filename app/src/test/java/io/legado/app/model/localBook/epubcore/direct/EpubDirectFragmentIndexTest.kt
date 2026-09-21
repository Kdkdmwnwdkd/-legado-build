package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubDirectFragmentIndexTest {

    private val boundaries = listOf(
        EpubDirectFragmentBoundary(4, null, "part-2"),
        EpubDirectFragmentBoundary(5, "part-2", "part-3"),
        EpubDirectFragmentBoundary(6, "part-3", null)
    )

    @Test
    fun `arbitrary id resolves to containing logical chapter`() {
        val index = EpubDirectFragmentIndex.parse(
            """<html><body><p id="intro">A</p><h2 id="part-2">B</h2>""" +
                """<p id="inside-2">C</p><h2 id="part-3">D</h2><p id="inside-3">E</p></body></html>"""
        )

        assertEquals(4, index.owner("intro", boundaries, 0))
        assertEquals(5, index.owner("inside-2", boundaries, 0))
        assertEquals(6, index.owner("inside-3", boundaries, 0))
    }

    @Test
    fun `legacy name and xml id participate in document order`() {
        val index = EpubDirectFragmentIndex.parse(
            """<html><body><p>Start</p><a name="part-2"/><p xml:id="inside-2">Text</p>""" +
                """<h2 id="part-3">Next</h2></body></html>"""
        )

        assertEquals(5, index.owner("inside-2", boundaries, 0))
    }

    @Test
    fun `exact chapter start does not depend on source index`() {
        val index = EpubDirectFragmentIndex.parse("<html><body/></html>")

        assertEquals(5, index.owner("part-2", boundaries, 0))
    }

    @Test
    fun `unknown fragment keeps current candidate before path fallback`() {
        val index = EpubDirectFragmentIndex.parse("""<html><body><p id="known"/></body></html>""")

        assertEquals(5, index.owner("missing", boundaries, 5))
        assertEquals(4, index.owner("missing", boundaries, 99))
    }
}
