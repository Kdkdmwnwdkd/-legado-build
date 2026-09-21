package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.help.ImageSourceOptions
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TextReaderImageResolverTest {
    private val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"24\"><text y=\"20\">12+🌅</text></svg>"
    private val data = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray())
    private fun image() = TextReaderImageResource.bytes(svg.toByteArray())

    @Test fun `data placeholders retain loading js and produce the resolved SVG rather than the placeholder`() = runBlocking {
        val raw = "data:image/svg+xml;base64," + GSON.toJson(mapOf(
            "js" to "makeImage(book.name, java.get('title'))", "style" to "TEXT", "click" to "openComment()"
        )).let { ",$it" }
        val content = TextReaderDocument.prepare("", "正文<img src=\"$raw\">继续")
        val source = content.blocks.single().inlineImages.single().image
        assertNull(TextReaderImageSource.browserDataImage(source.renderSource))
        assertEquals("makeImage(book.name, java.get('title'))", ImageSourceOptions.parse(source.renderSource)!!.option("js"))
        var calls = 0
        val resolver = TextReaderImageResolver(
            analyze = { request ->
                calls++
                assertEquals(source.renderSource, request)
                TextReaderImageResolver.Request(data) { error("data SVG must not be fetched as HTTP") }
            }, local = { error("not local") }, bubble = { error("not bubble") }
        )
        val resolved = resolver.load(source.renderSource)
        assertEquals(1, calls)
        assertEquals("image/svg+xml", resolved.mimeType)
        assertEquals(svg, resolved.response().stream.reader().use { it.readText() })
        assertEquals(TextReaderImageAction(raw, "openComment()"), content.imageActions().values.single())
    }

    @Test fun `dp and managed bubbles render without network and never regain paragraph actions`() = runBlocking {
        val raw = "dp:12,{\"displayText\":\"9+\",\"status\":\"emphasis\",\"color\":\"#aabbcc\",\"pclick\":\"rule:1:no()\"}"
        val content = TextReaderDocument.prepare("", "<p>正文<img src='$raw'>结尾</p>")
        assertTrue(content.imageActions().isEmpty())
        val source = content.blocks.single().inlineImages.single().image
        val rendered = arrayListOf<String>()
        val resolver = TextReaderImageResolver(
            analyze = { error("virtual images must not enter AnalyzeUrl") }, local = { error("not local") },
            bubble = { rendered.add(it); image() }
        )
        resolver.load(source.renderSource)
        resolver.load("BUBBLE://paragraph?num=8&status=normal")
        assertEquals("bubble://paragraph?displayText=9%2B&num=9%2B&status=emphasis&displayColor=%23aabbcc", rendered[0])
        assertEquals("bubble://paragraph?num=8&status=normal", rendered[1])
    }

    @Test fun `a loading rule may produce a managed bubble or a local gallery image`() = runBlocking {
        for (resolved in listOf("bubble://paragraph?num=5", "file:///storage/emulated/0/badge.png", "ai-image://image-5")) {
            val rendered = arrayListOf<String>()
            val resolver = TextReaderImageResolver(
                analyze = { TextReaderImageResolver.Request(resolved) { error("resolved special image fetched as HTTP") } },
                local = { rendered.add(it); image() }, bubble = { rendered.add(it); image() }
            )
            resolver.load("https://book/image,{\"js\":\"generate()\"}")
            assertEquals(listOf(resolved), rendered)
        }
    }

    @Test fun `HTTP options and body survive until the single native request`() = runBlocking {
        val raw = "../image,{\"headers\":{\"Referer\":\"https://book/chapter\"},\"body\":{\"id\":7},\"method\":\"POST\",\"js\":\"result\",\"click\":\"open()\"}"
        val source = TextReaderDocument.prepare("", "<img src=\"$raw\">").blocks.single().image!!
        var fetches = 0
        val resolver = TextReaderImageResolver(
            analyze = { request ->
                val options = ImageSourceOptions.parse(request)!!
                assertEquals("{\"Referer\":\"https://book/chapter\"}", options.option("headers"))
                assertEquals("{\"id\":7}", options.option("body"))
                assertEquals("POST", options.option("method"))
                assertNull(options.click)
                TextReaderImageResolver.Request("https://book/image") { fetches++; image() }
            }, local = { error("not local") }, bubble = { error("not bubble") }
        )
        assertEquals("image/svg+xml", resolver.load(source.renderSource).mimeType)
        assertEquals(1, fetches)
    }

    @Test fun `embedded image loading rules in URLs and nested request options cannot bypass analysis`() = runBlocking {
        val rules = listOf(
            "prefix@Js:generateImage()",
            "https://book/images/<Js>generateImage()</jS>",
            "https://book/images/{{book.name}}.svg",
            "../image," + GSON.toJson(mapOf("headers" to mapOf("X-Token" to "@js:readToken()"))),
            "../image," + GSON.toJson(mapOf("headers" to mapOf("X-Token" to "<js>readToken()</js>"))),
            "../image," + GSON.toJson(mapOf("body" to mapOf("token" to "{{java.get('token')}}"))),
            data + "," + GSON.toJson(mapOf("headers" to mapOf("Referer" to "{{java.get('ref')}}"))),
            data + "," + GSON.toJson(mapOf("js" to "generateImage()"))
        )
        for (raw in rules) {
            assertTrue(raw, TextReaderImageSource.needsScript(ImageSourceOptions.parse(raw)!!))
            assertNull(raw, TextReaderImageSource.browserDataImage(raw))
            val analyzed = arrayListOf<String>()
            val resolver = TextReaderImageResolver(
                analyze = {
                    analyzed.add(it)
                    TextReaderImageResolver.Request(data) { error("generated data must not be fetched") }
                },
                local = { error("a loading rule must not bypass source analysis") },
                bubble = { error("a loading rule must not bypass source analysis") },
                containerImage = { error("a loading rule must not bypass source analysis") }
            )
            assertEquals(svg, resolver.load(raw).response().stream.reader().use { it.readText() })
            assertEquals(listOf(raw), analyzed)
        }
    }

    @Test fun `literal SVG templates and presentation actions never become image loading scripts`() = runBlocking {
        val template = """<svg xmlns="http://www.w3.org/2000/svg"><metadata>@js:literal<js>template only</js></metadata><text>{{num}}</text></svg>"""
        val templateData = "data:image/svg+xml,$template"
        val options = mapOf(
            "cLiCk" to "@js:sourceClick()",
            "OnClick" to "<js>sourceClick()</js>",
            "pClIcK" to "{{paragraphAction()}}",
            "STYLE" to "TEXT",
            "WIDTH" to "{{20}}",
            "HEIGHT" to "@js:20"
        )
        val resolver = TextReaderImageResolver(
            analyze = { error("literal SVG or click metadata was executed as a loading script") },
            local = { error("not a local file") },
            bubble = { error("not a managed bubble") }
        )
        for (raw in listOf(templateData, templateData + "," + GSON.toJson(options))) {
            assertFalse(TextReaderImageSource.needsScript(ImageSourceOptions.parse(raw)!!))
            assertEquals(templateData, TextReaderImageSource.browserDataImage(raw))
            assertEquals(template, resolver.load(raw).response().stream.reader().use { it.readText() })
        }
    }

    @Test fun `managed static images retain original metadata and HTML style while respecting the preference`() = runBlocking {
        val action = "openReview()"
        val raw = data + "," + GSON.toJson(mapOf("displayText" to "9+", "pclick" to "rule:8:blocked()"))
        val content = TextReaderDocument.prepare("", "<p>正文<img src='$raw' style='TEXT' click='$action'>结尾</p>")
        val source = content.blocks.single().inlineImages.single().image
        assertEquals("text", source.sourceStyle)
        assertNull(ImageSourceOptions.parse(source.renderSource)!!.style)
        assertNull(ImageSourceOptions.parse(source.renderSource)!!.click)
        for (enabled in listOf(false, true)) {
            val rendered = arrayListOf<String>()
            val managed = TextReaderImageResource.bytes(
                svg.replace("12+🌅", "managed").toByteArray(), scale = 1.25f, isBubble = true
            )
            val resolver = TextReaderImageResolver(
                analyze = { error("static data SVG must not execute image loading rules") },
                local = { error("not local") },
                bubble = { rendered.add(it); managed },
                managedBubble = { resolved ->
                    assertEquals(data, resolved)
                    TextReaderSourceBubblePolicy.resolve(
                        source.source, resolved, source.sourceStyle, source.click, enabled
                    )
                }
            )
            val resource = resolver.load(source.renderSource)
            assertEquals(enabled, resource.isBubble)
            if (enabled) {
                assertSame(managed, resource)
                assertEquals(listOf("bubble://paragraph?displayText=9%2B&num=9%2B&status=normal"), rendered)
            } else {
                assertTrue(rendered.isEmpty())
                assertEquals(svg, resource.response().stream.reader().use { it.readText() })
            }
            assertEquals(TextReaderImageAction(raw, action), content.imageActions().values.single())
            assertEquals(listOf("正文结尾"), content.paragraphs(false))
        }
    }

    @Test fun `managed generated SVGs are selected after one loading rule without replacing the source action`() = runBlocking {
        val raw = "placeholder," + GSON.toJson(mapOf(
            "style" to "TEXT", "js" to "generateImage(book.name)", "click" to "openReview()",
            "pclick" to "rule:8:blocked()"
        ))
        val content = TextReaderDocument.prepare("", "<p>正文<img src='$raw'>结尾</p>")
        val source = content.blocks.single().inlineImages.single().image
        for (enabled in listOf(false, true)) {
            var analyses = 0
            val candidates = arrayListOf<String>()
            val rendered = arrayListOf<String>()
            val resolver = TextReaderImageResolver(
                analyze = { request ->
                    analyses++
                    assertEquals(source.renderSource, request)
                    val options = ImageSourceOptions.parse(request)!!
                    assertEquals("generateImage(book.name)", options.option("js"))
                    assertNull(options.style)
                    assertNull(options.click)
                    assertNull(options.option("pclick"))
                    TextReaderImageResolver.Request(data) { error("resolved SVG cannot become a network request") }
                },
                local = { error("not local") },
                bubble = { rendered.add(it); image() },
                managedBubble = { resolved ->
                    candidates.add(resolved)
                    TextReaderSourceBubblePolicy.resolve(
                        source.source, resolved, source.sourceStyle, source.click, enabled
                    )
                }
            )
            assertEquals("image/svg+xml", resolver.load(source.renderSource).mimeType)
            assertEquals(1, analyses)
            assertEquals(listOf(data), candidates)
            assertEquals(
                if (enabled) listOf("bubble://paragraph?displayText=12%2B%F0%9F%8C%85&num=12%2B%F0%9F%8C%85&status=normal")
                else emptyList<String>(),
                rendered
            )
            assertEquals(TextReaderImageAction(raw, "openReview()"), content.imageActions().values.single())
        }
    }

    @Test fun `existing absolute local files and root relative HTTP images keep separate routes`() = runBlocking {
        val localPath = "/storage/emulated/0/Pictures/badge.svg"
        val locals = arrayListOf<String>()
        val network = arrayListOf<String>()
        val resolver = TextReaderImageResolver(
            analyze = {
                network.add(it)
                TextReaderImageResolver.Request("https://book$it") { image() }
            },
            local = { locals.add(it); image() },
            bubble = { error("not a bubble") },
            isLocalFile = { it == localPath }
        )
        resolver.load(localPath)
        resolver.load("/images/remote.svg")
        assertEquals(listOf(localPath), locals)
        assertEquals(listOf("/images/remote.svg"), network)
    }

    @Test fun `MOBI resource ids reach their container unchanged`() = runBlocking {
        val sources = arrayListOf<String>()
        val resolver = TextReaderImageResolver(
            analyze = { error("container ids cannot become remote URLs") },
            local = { error("not local") }, bubble = { error("not bubble") },
            containerImage = { sources.add(it); image() }
        )
        resolver.load("kindle:embed:0003?mime=image/jpg")
        resolver.load("recindex:17")
        assertEquals(listOf("kindle:embed:0003?mime=image/jpg", "recindex:17"), sources)
    }

    @Test fun `percent and base64 SVGs retain plus signs unicode and their actual MIME`() {
        val encoded = java.net.URLEncoder.encode(svg, "UTF-8").replace("+", "%20")
        for (src in listOf(data, "data:image/svg+xml,$encoded", "data:image/svg+xml,$svg")) {
            val loaded = TextReaderImageSource.dataImage(src)!!
            assertEquals(svg, loaded.response().stream.reader().use { it.readText() })
        }
        val file = File.createTempFile("source-svg", ".jpg")
        try {
            file.writeText("\ufeff<?xml version=\"1.0\"?>\n$svg")
            assertEquals("image/svg+xml", TextReaderImageResource.file(file).mimeType)
        } finally { file.delete() }
    }

    @Test(expected = IOException::class) fun `an HTML error response cannot become a cached image`() {
        TextReaderImageResource.bytes("<html><body>Unauthorized</body></html>".toByteArray())
    }

    @Test fun `atomic replacement publishes the whole new image after the temporary copy completes`() {
        val oldBytes = svg.toByteArray()
        val newBytes = svg.replace("12+", "34+").toByteArray()
        assertEquals(oldBytes.size, newBytes.size)
        val directory = Files.createTempDirectory("text-image-replace").toFile()
        val target = File(directory, "cache.jpg").apply { writeBytes(oldBytes) }
        val copying = CountDownLatch(1)
        val completeCopy = CountDownLatch(1)
        val replacement = TextReaderImageResource("image/svg+xml", newBytes.size.toLong()) {
            object : ByteArrayInputStream(newBytes) {
                private var reads = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ == 1) {
                        copying.countDown()
                        check(completeCopy.await(5, TimeUnit.SECONDS)) { "image copy fixture timed out" }
                    }
                    return super.read(buffer, offset, minOf(length, 16))
                }
            }
        }
        val writer = Executors.newSingleThreadExecutor()
        val pending = writer.submit<TextReaderImageResource> { replacement.persist(target, replaceExisting = true) }
        try {
            assertTrue("replacement did not reach its partial temporary copy", copying.await(5, TimeUnit.SECONDS))
            assertArrayEquals(oldBytes, target.readBytes())
            completeCopy.countDown()
            val persisted = pending.get(5, TimeUnit.SECONDS)
            assertArrayEquals(newBytes, target.readBytes())
            assertArrayEquals(newBytes, persisted.response().stream.use { it.readBytes() })
            assertEquals(0, persisted.retainedBytes)
        } finally {
            completeCopy.countDown()
            pending.cancel(true)
            writer.shutdownNow()
            writer.awaitTermination(5, TimeUnit.SECONDS)
            target.delete()
            directory.delete()
        }
    }

    @Test fun `failed replacement keeps the previous complete image usable and removes the partial copy`() {
        val oldBytes = svg.toByteArray()
        val newBytes = svg.replace("12+", "34+").toByteArray()
        val directory = Files.createTempDirectory("text-image-failed-replace").toFile()
        val target = File(directory, "cache.jpg").apply { writeBytes(oldBytes) }
        val replacement = TextReaderImageResource("image/svg+xml", newBytes.size.toLong()) {
            object : ByteArrayInputStream(newBytes) {
                private var reads = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ > 0) throw IOException("injected source stream failure")
                    return super.read(buffer, offset, minOf(length, 16))
                }
            }
        }
        try {
            assertThrows(IOException::class.java) { replacement.persist(target, replaceExisting = true) }
            assertArrayEquals(oldBytes, target.readBytes())
            assertEquals("image/svg+xml", TextReaderImageResource.file(target).mimeType)
            assertEquals(listOf("cache.jpg"), directory.listFiles()!!.map { it.name })
        } finally {
            target.delete()
            directory.delete()
        }
    }

    @Test fun `an existing image stream stays complete during replacement or Windows denial followed by retry`() {
        val oldBytes = svg.toByteArray()
        val newBytes = svg.replace("12+", "34+").toByteArray()
        val directory = Files.createTempDirectory("text-image-open-replace").toFile()
        val target = File(directory, "cache.jpg").apply { writeBytes(oldBytes) }
        val replacement = TextReaderImageResource.bytes(newBytes)
        var deniedWhileOpen = false
        try {
            target.inputStream().use { oldStream ->
                try {
                    replacement.persist(target, replaceExisting = true)
                } catch (error: AccessDeniedException) {
                    // Windows FileInputStream can deny delete sharing. Exercise
                    // preservation and mandatory retry instead of skipping this
                    // branch; this JVM check is not Android-device validation.
                    if (!System.getProperty("os.name").startsWith("Windows", true)) throw error
                    deniedWhileOpen = true
                }
                assertArrayEquals(oldBytes, oldStream.readBytes())
                assertArrayEquals(if (deniedWhileOpen) oldBytes else newBytes, target.readBytes())
            }
            if (deniedWhileOpen) {
                val retried = replacement.persist(target, replaceExisting = true)
                assertArrayEquals(newBytes, retried.response().stream.use { it.readBytes() })
            }
            assertArrayEquals(newBytes, target.readBytes())
            assertEquals(listOf("cache.jpg"), directory.listFiles()!!.map { it.name })
        } finally {
            target.delete()
            directory.delete()
        }
    }

    @Test fun `large generated resources persist exact bytes MIME and bubble size without retaining their buffer`() {
        val bytes = svg.replace("<text", "<!--" + "large-image ".repeat(6000) + "--><text").toByteArray()
        val expected = bytes.copyOf()
        val resource = TextReaderImageResource.bytes(bytes, scale = 1.5f, isBubble = true)
        assertTrue(resource.retainedBytes > 64 * 1024)
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(expected)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        assertEquals(expectedHash, resource.contentHash())
        val directory = Files.createTempDirectory("text-image-persist").toFile()
        val target = File(directory, "nested/$expectedHash.jpg")
        try {
            val persisted = resource.persist(target)
            assertTrue(target.isFile)
            assertEquals(expected.size.toLong(), persisted.length)
            assertEquals(0, persisted.retainedBytes)
            assertEquals(1.5f, persisted.scale, 0f)
            assertTrue(persisted.isBubble)
            assertEquals("image/svg+xml", persisted.mimeType)
            assertEquals("image/svg+xml", TextReaderImageResource.file(target).mimeType)
            // Reusing identical image bytes as an ordinary source must not
            // inherit the software bubble's presentation metadata.
            assertFalse(TextReaderImageResource.file(target).isBubble)
            assertEquals(expectedHash, persisted.contentHash())
            repeat(2) {
                val response = persisted.response()
                assertEquals(expected.size.toString(), response.headers["Content-Length"])
                assertArrayEquals(expected, response.stream.use { it.readBytes() })
            }
            val head = persisted.response(headOnly = true)
            assertEquals(expected.size.toString(), head.headers["Content-Length"])
            assertEquals(-1, head.stream.use { it.read() })
            val reused = resource.persist(target)
            assertEquals(0, reused.retainedBytes)
            assertEquals(1.5f, reused.scale, 0f)
            assertTrue(reused.isBubble)
            bytes.fill(0)
            assertArrayEquals(expected, reused.response().stream.use { it.readBytes() })
            assertArrayEquals(expected, target.readBytes())
        } finally {
            target.delete()
            target.parentFile?.delete()
            directory.delete()
        }
    }

    @Test fun `deferred resources preserve inline bounds source clicks and character anchors`() {
        val raw = "<p>正文🌅<img src='dp:12,{\"click\":\"show()\"}'>尾声</p><img src='../image.png'>"
        val content = TextReaderDocument.prepare("标题", raw)
        val dom = Jsoup.parse(content.html(true, deferredImages = true) { "https://book.epub.local/text-image/0/" + it.length })
        val images = dom.select("img")
        assertEquals(2, images.size)
        assertTrue(images.all { it.attr("src").startsWith("data:image/") && it.attr("data-legado-image-state") == "pending" })
        assertEquals(1, dom.select("p img.legado-text-inline-image").size)
        assertEquals("show()", content.imageActions().values.single().click)
        assertFalse(dom.html().contains("show()"))
        val plain = content.plainText(true)
        dom.select("[data-legado-text-offset]").forEach { node ->
            val offset = node.attr("data-legado-text-offset").toInt()
            assertEquals(node.wholeText(), plain.substring(offset, offset + node.wholeText().length))
        }
    }
}
