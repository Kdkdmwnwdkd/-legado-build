package io.legado.app.model.localBook.epubcore.direct

import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class EpubDirectSessionTest {

    @Test
    fun `close requests cancellation immediately while a resource lease still owns cleanup`() {
        val cancellations = AtomicInteger()
        val closes = AtomicInteger()
        val session = session(
            resourceLoader = { _, _ -> resource(byteArrayOf(1)) },
            closeAction = { closes.incrementAndGet() },
            onCloseRequested = { cancellations.incrementAndGet() }
        )
        val resource = session.openResourcePath("image.jpg", null)!!
        session.close()
        session.close()
        assertEquals(1, cancellations.get())
        assertEquals(0, closes.get())
        resource.stream.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun `closed state is visible to session owners`() {
        val session = session(resourceLoader = { _, _ -> null })

        assertFalse(session.isClosed)
        session.close()

        assertTrue(session.isClosed)
    }

    @Test
    fun `same chapter preparation is coalesced`() {
        val loaderEntered = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val loads = AtomicInteger()
        val expected = chapter(1)
        val session = session(resourceLoader = { _, _ -> null })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<EpubDirectChapter> {
                session.prepareChapterForKey("chapter-1") {
                    loads.incrementAndGet()
                    loaderEntered.countDown()
                    releaseLoader.await()
                    expected
                }
            }
            assertTrue(loaderEntered.await(2, TimeUnit.SECONDS))
            val secondThread = AtomicReference<Thread>()
            val second = pool.submit<EpubDirectChapter> {
                secondThread.set(Thread.currentThread())
                session.prepareChapterForKey("chapter-1") {
                    loads.incrementAndGet()
                    error("second loader must be coalesced")
                }
            }
            val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (secondThread.get()?.state != Thread.State.WAITING &&
                !second.isDone &&
                System.nanoTime() < waitDeadline
            ) {
                Thread.sleep(1)
            }
            assertEquals(Thread.State.WAITING, secondThread.get()?.state)
            releaseLoader.countDown()

            assertSame(expected, first.get(2, TimeUnit.SECONDS))
            assertSame(expected, second.get(2, TimeUnit.SECONDS))
            assertEquals(1, loads.get())
        } finally {
            releaseLoader.countDown()
            session.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `different chapters prepare concurrently`() {
        val loadersEntered = CountDownLatch(2)
        val releaseLoaders = CountDownLatch(1)
        val session = session(resourceLoader = { _, _ -> null })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<EpubDirectChapter> {
                session.prepareChapterForKey("chapter-1") {
                    loadersEntered.countDown()
                    releaseLoaders.await()
                    chapter(1)
                }
            }
            val second = pool.submit<EpubDirectChapter> {
                session.prepareChapterForKey("chapter-2") {
                    loadersEntered.countDown()
                    releaseLoaders.await()
                    chapter(2)
                }
            }

            assertTrue(loadersEntered.await(2, TimeUnit.SECONDS))
            releaseLoaders.countDown()
            assertEquals(1, first.get(2, TimeUnit.SECONDS).chapterIndex)
            assertEquals(2, second.get(2, TimeUnit.SECONDS).chapterIndex)
        } finally {
            releaseLoaders.countDown()
            session.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `new chapter style replaces previous style in cache`() {
        val session = session(resourceLoader = { _, _ -> null })
        val old = chapter(1)
        val updated = old.copy(html = "<p>updated</p>")
        var oldReloads = 0

        assertSame(old, session.prepareChapterForKey("old-style", "chapter-1") { old })
        assertSame(updated, session.prepareChapterForKey("new-style", "chapter-1") { updated })
        assertSame(updated, session.prepareChapterForKey("new-style", "chapter-1") {
            error("new style should remain cached")
        })
        session.prepareChapterForKey("old-style", "chapter-1") {
            oldReloads++
            old
        }

        assertEquals(1, oldReloads)
        session.close()
    }

    @Test
    fun `slower obsolete style cannot evict newer style`() {
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val session = session(resourceLoader = { _, _ -> null })
        val pool = Executors.newSingleThreadExecutor()
        try {
            val oldLoad = pool.submit<EpubDirectChapter> {
                session.prepareChapterForKey("old-style", "chapter-1") {
                    oldStarted.countDown()
                    releaseOld.await()
                    chapter(1)
                }
            }
            assertTrue(oldStarted.await(2, TimeUnit.SECONDS))
            val updated = chapter(1).copy(html = "<p>updated</p>")
            assertSame(updated, session.prepareChapterForKey("new-style", "chapter-1") { updated })
            releaseOld.countDown()
            oldLoad.get(2, TimeUnit.SECONDS)

            assertSame(updated, session.prepareChapterForKey("new-style", "chapter-1") {
                error("obsolete completion must not evict current style")
            })
        } finally {
            releaseOld.countDown()
            session.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `different resources load concurrently through one session`() {
        val loadersEntered = CountDownLatch(2)
        val releaseLoaders = CountDownLatch(1)
        val session = session(
            resourceLoader = { path, _ ->
                loadersEntered.countDown()
                releaseLoaders.await()
                resource(path.toByteArray())
            }
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<EpubDirectResource?> {
                session.openResourcePath("a.css", null)
            }
            val second = pool.submit<EpubDirectResource?> {
                session.openResourcePath("b.css", null)
            }

            assertTrue(loadersEntered.await(2, TimeUnit.SECONDS))
            releaseLoaders.countDown()
            first.get(2, TimeUnit.SECONDS)?.stream?.close()
            second.get(2, TimeUnit.SECONDS)?.stream?.close()
        } finally {
            releaseLoaders.countDown()
            session.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `close waits for a loading resource lease`() {
        val loaderEntered = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val closes = AtomicInteger()
        val session = session(
            resourceLoader = { _, _ ->
                loaderEntered.countDown()
                releaseLoader.await()
                resource(byteArrayOf(1))
            },
            closeAction = { closes.incrementAndGet() }
        )
        val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit<EpubDirectResource?> {
                session.openResourcePath("image.jpg", null)
            }
            assertTrue(loaderEntered.await(2, TimeUnit.SECONDS))

            session.close()
            assertEquals(0, closes.get())
            releaseLoader.countDown()
            val loaded = pending.get(2, TimeUnit.SECONDS)
            assertEquals(0, closes.get())

            loaded?.stream?.close()
            assertEquals(1, closes.get())
        } finally {
            releaseLoader.countDown()
            session.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `failed resource load releases its lease`() {
        val closes = AtomicInteger()
        val session = session(
            resourceLoader = { _, _ -> error("damaged resource") },
            closeAction = { closes.incrementAndGet() }
        )

        val failure = runCatching {
            session.openResourcePath("damaged.css", null)
        }.exceptionOrNull()
        session.close()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, closes.get())
    }

    @Test
    fun `prepared chapter cache evicts by total character budget`() {
        val session = session(
            resourceLoader = { _, _ -> null },
            maxCachedChapterChars = 10
        )
        var firstLoads = 0
        val first = chapter(1).copy(html = "123456", plainText = "")
        val second = chapter(2).copy(html = "abcdef", plainText = "")

        session.prepareChapterForKey("first") { firstLoads++; first }
        session.prepareChapterForKey("second") { second }
        session.prepareChapterForKey("first") { firstLoads++; first }

        assertEquals(2, firstLoads)
        session.close()
    }

    @Test
    fun `oversized prepared chapter is returned without entering cache`() {
        val session = session(
            resourceLoader = { _, _ -> null },
            maxCachedChapterChars = 4
        )
        var loads = 0
        val oversized = chapter(1).copy(html = "12345", plainText = "")

        repeat(2) {
            assertSame(oversized, session.prepareChapterForKey("large") { loads++; oversized })
        }

        assertEquals(2, loads)
        session.close()
    }

    @Test
    fun `native image action payloads count toward the chapter cache budget`() {
        val session = session(resourceLoader = { _, _ -> null }, maxCachedChapterChars = 100)
        val payload = chapter(1).copy(
            html = "<img>", plainText = "text", sourceChapterUrl = "chapter-1",
            sourceImages = TextReaderSourceImages("source", true,
                mapOf("image-0" to TextReaderImageAction("data:image/svg+xml," + "x".repeat(200), "source()")))
        )
        var loads = 0
        repeat(2) {
            assertSame(payload, session.prepareChapterForKey("image-actions") { loads++; payload })
        }
        assertEquals(2, loads)
        session.close()
    }

    @Test
    fun `an image render revision reloads its chapter while an unchanged revision reuses it`() {
        val revision = AtomicReference("bubble-a")
        val loads = AtomicInteger()
        val session = EpubDirectSession(
            bookUrl = "ordinary-book",
            chapterLoader = { index, _ ->
                loads.incrementAndGet()
                chapter(index).copy(html = "<p>${revision.get()}</p>")
            },
            resourceLoader = { _, _ -> null },
            linkResolver = { _, _ -> null },
            closeAction = {},
            chapterRevisionProvider = revision::get
        )
        val config = layoutConfig()
        try {
            val first = session.prepareChapter(1, config)
            assertSame(first, session.prepareChapter(1, config))
            revision.set("bubble-b")
            val updated = session.prepareChapter(1, config)
            assertNotSame(first, updated)
            assertEquals("<p>bubble-b</p>", updated.html)
            assertEquals(first.chapterIndex, updated.chapterIndex)
            assertEquals(first.href, updated.href)
            assertEquals(first.plainText, updated.plainText)
            assertSame(updated, session.prepareChapter(1, config))
            assertEquals(2, loads.get())
            // Reverting the package creates a new preparation, not a stale
            // metadata map retained from before the intervening revision.
            revision.set("bubble-a")
            assertNotSame(first, session.prepareChapter(1, config))
            assertEquals(3, loads.get())
        } finally { session.close() }
    }

    @Test
    fun `publisher EPUB preparation stays cached when ordinary image settings change`() {
        val revision = AtomicReference("bubble-a")
        val ordinaryLoads = AtomicInteger()
        val publisherLoads = AtomicInteger()
        val ordinary = EpubDirectSession(
            bookUrl = "ordinary-book",
            chapterLoader = { index, _ -> ordinaryLoads.incrementAndGet(); chapter(index) },
            resourceLoader = { _, _ -> null },
            linkResolver = { _, _ -> null },
            closeAction = {},
            chapterRevisionProvider = revision::get
        )
        // Intentionally use the production constructor's default provider.
        val publisher = EpubDirectSession(
            bookUrl = "publisher.epub",
            chapterLoader = { index, _ -> publisherLoads.incrementAndGet(); chapter(index) },
            resourceLoader = { _, _ -> null },
            linkResolver = { _, _ -> null },
            closeAction = {}
        )
        val config = layoutConfig()
        try {
            ordinary.prepareChapter(1, config)
            val firstPublisher = publisher.prepareChapter(1, config)
            revision.set("bubble-b")
            ordinary.prepareChapter(1, config)
            assertSame(firstPublisher, publisher.prepareChapter(1, config))
            assertEquals(2, ordinaryLoads.get())
            assertEquals(1, publisherLoads.get())
        } finally {
            ordinary.close()
            publisher.close()
        }
    }

    @Test
    fun `a late image revision cannot replace a more recently prepared chapter`() {
        val revision = AtomicReference("bubble-a")
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val loads = AtomicInteger()
        val session = EpubDirectSession(
            bookUrl = "ordinary-book",
            chapterLoader = { index, _ ->
                val capturedRevision = revision.get()
                loads.incrementAndGet()
                if (capturedRevision == "bubble-a") {
                    firstStarted.countDown()
                    releaseFirst.await()
                }
                chapter(index).copy(html = "<p>$capturedRevision</p>")
            },
            resourceLoader = { _, _ -> null },
            linkResolver = { _, _ -> null },
            closeAction = {},
            chapterRevisionProvider = revision::get
        )
        val config = layoutConfig()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val oldPreparation = pool.submit<EpubDirectChapter> { session.prepareChapter(1, config) }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            revision.set("bubble-b")
            val current = session.prepareChapter(1, config)
            releaseFirst.countDown()
            assertEquals("<p>bubble-a</p>", oldPreparation.get(5, TimeUnit.SECONDS).html)
            assertEquals("<p>bubble-b</p>", current.html)
            assertSame(current, session.prepareChapter(1, config))
            assertEquals(2, loads.get())
        } finally {
            releaseFirst.countDown()
            session.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `images without click actions still count toward the prepared chapter cache budget`() {
        fun imageChapter(source: String): EpubDirectChapter {
            val image = TextReaderImage(
                id = "image-0", source = source, renderSource = source, click = null,
                inline = true, width = null, height = null, alignment = null
            )
            val request = TextReaderImageRequest(1, "chapter-1", image, "r1", false)
            return chapter(1).copy(
                html = "<img>", plainText = "",
                sourceImages = TextReaderSourceImages(
                    sourceKey = null, onlineText = true, actions = emptyMap(),
                    resources = mapOf("text-image/1/r1/image-0" to request)
                )
            )
        }
        val session = session(resourceLoader = { _, _ -> null }, maxCachedChapterChars = 256)
        val large = imageChapter("data:image/svg+xml," + "x".repeat(1024))
        val small = imageChapter("cover.png")
        var largeLoads = 0
        var smallLoads = 0
        try {
            repeat(2) {
                assertSame(large, session.prepareChapterForKey("large-resource") { largeLoads++; large })
            }
            repeat(2) {
                assertSame(small, session.prepareChapterForKey("small-resource") { smallLoads++; small })
            }
            assertEquals(2, largeLoads)
            assertEquals(1, smallLoads)
        } finally { session.close() }
    }

    private fun layoutConfig() = EpubCoreLayoutConfig(
        pageWidthPx = 400,
        pageHeightPx = 800,
        textPaint = object : TextPaint() {
            override fun getTextSize() = 16f
            override fun getLetterSpacing() = 0f
            override fun getColor() = 0xFF000000.toInt()
        },
        backgroundColor = 0xFFFFFFFF.toInt(),
        selectionColor = 0x14000000
    )

    private fun session(
        resourceLoader: (String, String?) -> EpubDirectResource?,
        closeAction: () -> Unit = {},
        maxCachedChapterChars: Int = 8 * 1024 * 1024,
        onCloseRequested: () -> Unit = {}
    ): EpubDirectSession {
        return EpubDirectSession(
            bookUrl = "book",
            chapterLoader = { _, _ -> error("unused") },
            resourceLoader = resourceLoader,
            linkResolver = { _, _ -> null },
            closeAction = closeAction,
            maxCachedChapterChars = maxCachedChapterChars,
            onCloseRequested = onCloseRequested
        )
    }

    private fun resource(bytes: ByteArray): EpubDirectResource {
        return EpubDirectResource(
            mimeType = "application/octet-stream",
            encoding = null,
            statusCode = 200,
            reasonPhrase = "OK",
            headers = emptyMap(),
            stream = ByteArrayInputStream(bytes)
        )
    }

    private fun chapter(index: Int): EpubDirectChapter {
        return EpubDirectChapter(
            chapterIndex = index,
            href = "$index.xhtml",
            title = "Chapter $index",
            baseUrl = "https://epub.local/$index.xhtml",
            html = "<p>$index</p>",
            plainText = index.toString(),
            startFragmentId = null,
            endFragmentId = null,
            layoutMode = EpubDirectLayoutMode.REFLOWABLE,
            viewportWidth = null,
            viewportHeight = null,
            publisherOrientation = "auto",
            publisherSpread = "auto",
            publisherFullscreen = false,
            fullPageArtwork = false,
            implicitSinglePage = false,
            duokanGallery = false,
            scripted = false,
            pageProgressionDirection = null
        )
    }
}
