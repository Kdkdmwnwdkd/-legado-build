package io.legado.app.model.localBook.epubcore.direct

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64

class TextReaderBubblePreparationTest {
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j9l8AAAAASUVORK5CYII="
    )
    private val svg = "data:image/svg+xml," +
        "%3Csvg xmlns='http://www.w3.org/2000/svg' width='64' height='24'%3E%3Ctext y='20'%3E12%3C/text%3E%3C/svg%3E"

    private fun image(id: String, raw: String, click: String? = null, style: String = "text"): TextReaderImage =
        requireNotNull(TextReaderImage.fromAttributes(id, buildMap {
            put("src", raw)
            put("style", style)
            click?.let { put("click", it) }
        }))

    private fun pixels(scale: Float = 1.25f) = TextReaderImageResource.bytes(png, scale, isBubble = true)

    @Test fun `static managed data SVG becomes final PNG with scale and original action unchanged`() = runBlocking {
        val input = image("image-0", svg, "openReview()")
        val rendered = mutableListOf<String>()
        val prepared = TextReaderBubblePreparation(render = { canonical ->
            rendered.add(canonical)
            pixels()
        }).prepare(listOf(input), managedBubble = true)
        assertEquals(listOf("bubble://paragraph?displayText=12&num=12&status=normal"), rendered)
        val result = prepared.getValue(input.id)
        assertTrue(result.dataUri.startsWith("data:image/png;base64,"))
        assertArrayEquals(png, Base64.getDecoder().decode(result.dataUri.substringAfter(',')))
        assertEquals(1.25f, result.scale, 0f)
        assertTrue(result.isBubble)
        assertEquals(svg, input.source)
        assertEquals("openReview()", input.click)
        assertEquals("image-0", input.id)
    }

    @Test fun `disabled software styling preserves source SVG but explicit virtual bubbles still prepare`() = runBlocking {
        val rendered = mutableListOf<String>()
        val preparation = TextReaderBubblePreparation(render = { rendered.add(it); pixels() })
        val result = preparation.prepare(listOf(image("data", svg), image("virtual", "dp:7")), managedBubble = false)
        assertEquals(setOf("virtual"), result.keys)
        assertEquals(listOf("bubble://paragraph?displayText=7&num=7&status=normal"), rendered)
    }

    @Test fun `ordinary remote local and raster images do not start preparation work`() = runBlocking {
        val preparation = TextReaderBubblePreparation(render = { error("Ordinary images cannot enter the bubble renderer") })
        val result = preparation.prepare(listOf(
            image("remote", "https://source.example/photo.png"),
            image("remote-svg", "https://source.example/diagram.svg", "java.toast(1)"),
            image("local", "file:///storage/emulated/0/photo.png"),
            image("raster", "data:image/png;base64," + Base64.getEncoder().encodeToString(png)),
            image("standalone-svg", svg, style = "center")
        ), managedBubble = true)
        assertTrue(result.isEmpty())
    }

    @Test fun `loading scripts are checked before data and explicit bubble recognition`() = runBlocking {
        val preparation = TextReaderBubblePreparation(render = { error("Loading scripts must keep their normal analysis path") })
        val result = preparation.prepare(listOf(
            image("data", svg + ",{\"js\":\"makeImage(book.name)\"}"),
            image("virtual", "bubble://paragraph?num=7,{\"js\":\"changeCount()\"}"),
            image("dp", "dp:7,{\"js\":\"changeCount()\"}"),
            image("request", "https://source.example/badge?num=7,{\"type\":\"comment\",\"headers\":{\"X\":\"{{book.name}}\"}}")
        ), managedBubble = true)
        assertTrue(result.isEmpty())
    }

    @Test fun `a static count on a remote-looking source follows the existing managed special route`() = runBlocking {
        val rendered = mutableListOf<String>()
        val result = TextReaderBubblePreparation(render = { rendered.add(it); pixels() }).prepare(listOf(
            image("badge", "https://source.example/badge?num=9,{\"type\":\"comment\"}")
        ), managedBubble = true)
        assertEquals(setOf("badge"), result.keys)
        assertEquals(listOf("bubble://paragraph?displayText=9&num=9&status=normal"), rendered)
    }

    @Test fun `identical canonical badges share pixels while keeping separate image identities`() = runBlocking {
        var calls = 0
        val first = image("first", "dp:7", "openFirst()")
        val second = image("second", "dp:7", "openSecond()")
        val result = TextReaderBubblePreparation(render = { calls++; pixels() })
            .prepare(listOf(first, second), managedBubble = true)
        assertEquals(1, calls)
        assertEquals(setOf("first", "second"), result.keys)
        assertSame(result["first"], result["second"])
        assertEquals("openFirst()", first.click)
        assertEquals("openSecond()", second.click)
    }

    @Test fun `a failed canonical badge is reported once and does not block other badges`() = runBlocking {
        val rendered = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val result = TextReaderBubblePreparation(render = {
            rendered.add(it)
            if (it.contains("displayText=7")) throw IOException("broken template")
            pixels()
        }, onFailure = { canonical, _ -> failures.add(canonical) }).prepare(listOf(
            image("bad-first", "dp:7"), image("bad-second", "dp:7"), image("good", "dp:8")
        ), managedBubble = true)
        assertEquals(2, rendered.size)
        assertEquals(1, failures.size)
        assertEquals(setOf("good"), result.keys)
    }

    @Test fun `the unique source budget still permits later repetitions of a completed badge`() = runBlocking {
        var calls = 0
        val result = TextReaderBubblePreparation(render = { calls++; pixels() }, maxUniqueBubbles = 1).prepare(listOf(
            image("one", "dp:1"), image("new", "dp:2"), image("repeat", "dp:1")
        ), managedBubble = true)
        assertEquals(1, calls)
        assertEquals(setOf("one", "repeat"), result.keys)
    }

    @Test fun `oversized single images fall back without repeating generation for the same badge`() = runBlocking {
        var calls = 0
        val result = TextReaderBubblePreparation(render = { calls++; pixels() }, maxImageBytes = png.size - 1)
            .prepare(listOf(image("one", "dp:1"), image("repeat", "dp:1")), managedBubble = true)
        assertEquals(1, calls)
        assertTrue(result.isEmpty())
    }

    @Test fun `total byte and HTML budgets count repeated occurrences of shared pixels`() = runBlocking {
        val inputs = listOf(image("one", "dp:1"), image("repeat", "dp:1"))
        var byteCalls = 0
        val bytes = TextReaderBubblePreparation(render = { byteCalls++; pixels() }, maxInlineBytes = png.size.toLong())
            .prepare(inputs, managedBubble = true)
        assertEquals(setOf("one"), bytes.keys)
        assertEquals(1, byteCalls)
        var charCalls = 0
        val chars = TextReaderBubblePreparation(render = { charCalls++; pixels() },
            maxInlineChars = requireNotNull(pixels().dataUri(png.size)).length.toLong())
            .prepare(inputs, managedBubble = true)
        assertEquals(setOf("one"), chars.keys)
        assertEquals(1, charCalls)
    }

    @Test fun `batch timeout preserves completed badges and leaves remaining work deferred`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val result = TextReaderBubblePreparation(render = {
            if (it.contains("displayText=2")) delay(10_000)
            pixels()
        }, timeoutMs = 500L, onFailure = { _, error -> failures.add(error) }).prepare(listOf(
            image("ready", "dp:1"), image("slow", "dp:2"), image("later", "dp:3")
        ), managedBubble = true)
        currentCoroutineContext().ensureActive()
        assertEquals(setOf("ready"), result.keys)
        assertTrue(failures.isEmpty())
    }

    @Test fun `pixels returned after a noncooperative render exceeds its batch budget are not published`() = runBlocking {
        val result = TextReaderBubblePreparation(render = {
            withContext(NonCancellable) { delay(250L) }
            pixels()
        }, timeoutMs = 50L).prepare(listOf(image("late", "dp:1")), managedBubble = true)
        currentCoroutineContext().ensureActive()
        assertTrue(result.isEmpty())
    }

    @Test fun `caller cancellation propagates instead of returning a fallback map`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var returned = false
        var failures = 0
        val preparation = TextReaderBubblePreparation(render = {
            entered.complete(Unit)
            awaitCancellation()
        }, onFailure = { _, _ -> failures++ })
        val task = async {
            preparation.prepare(listOf(image("cancelled", "dp:1")), managedBubble = true).also { returned = true }
        }
        entered.await()
        task.cancel()
        try {
            task.await()
            fail("Caller cancellation must propagate")
        } catch (_: CancellationException) {
        }
        assertFalse(returned)
        assertEquals(0, failures)
    }

    @Test fun `bounded data URI export checks both declared and streamed byte counts`() {
        var opened = false
        val tooLarge = TextReaderImageResource("image/png", 1000L) {
            opened = true
            ByteArrayInputStream(png)
        }
        assertNull(tooLarge.dataUri(8))
        assertFalse(opened)
        var closed = false
        val changed = TextReaderImageResource("image/png", 1L) {
            object : ByteArrayInputStream(ByteArray(32)) {
                override fun close() { closed = true; super.close() }
            }
        }
        assertNull(changed.dataUri(8))
        assertTrue(closed)
        val truncated = TextReaderImageResource("image/png", 4L) { ByteArrayInputStream(byteArrayOf(1, 2)) }
        assertNull(truncated.dataUri(8))
    }
}
