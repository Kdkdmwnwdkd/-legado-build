package io.legado.app.ui.book.read.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDirectFailureDiagnosticsTest {

    @Test
    fun `fallback is retained when no throwable is available`() {
        assertEquals(
            "EPUB preparation failed",
            EpubDirectFailureDiagnostics.userMessage("EPUB preparation failed", null)
        )
    }

    @Test
    fun `initializer root cause is shown before its wrapper`() {
        val root = IllegalStateException("invalid static state")
        val wrapper = ExceptionInInitializerError(root)
        root.stackTrace = arrayOf(
            StackTraceElement(
                "io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder",
                "<clinit>",
                "EpubDirectDocumentBuilder.kt",
                859
            )
        )

        val message = EpubDirectFailureDiagnostics.userMessage("ignored", wrapper)

        assertTrue(message.startsWith("java.lang.IllegalStateException: invalid static state"))
        assertTrue(message.contains("Wrapped by java.lang.ExceptionInInitializerError"))
        assertTrue(
            message.contains(
                "At io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder" +
                    ".<clinit>(EpubDirectDocumentBuilder.kt:859)"
            )
        )
    }

    @Test
    fun `log chain preserves every available cause`() {
        val root = IllegalArgumentException("bad pattern")
        val middle = IllegalStateException("builder failed", root)
        val wrapper = RuntimeException("prepare failed", middle)

        assertEquals(
            "java.lang.RuntimeException: prepare failed <- " +
                "java.lang.IllegalStateException: builder failed <- " +
                "java.lang.IllegalArgumentException: bad pattern",
            EpubDirectFailureDiagnostics.logCauseChain(wrapper)
        )
        val chain = EpubDirectFailureDiagnostics.causeChain(wrapper)
        assertEquals(3, chain.size)
        assertSame(root, chain.last())
    }

    @Test
    fun `blank exception messages still expose the exception class`() {
        assertEquals(
            "java.lang.IllegalStateException",
            EpubDirectFailureDiagnostics.userMessage("ignored", IllegalStateException())
        )
    }
}
