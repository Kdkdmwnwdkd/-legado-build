package io.legado.app.model.localBook.epubcore.direct

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TextReaderImageRegistryTest {
    private fun image() = TextReaderImage(
        id = "image-0",
        source = "https://images.example/shared.png",
        renderSource = "https://images.example/shared.png",
        click = "java.toast('book-a')",
        inline = true,
        width = "1em",
        height = null,
        alignment = null,
        sourceStyle = "text"
    )

    private fun request() = TextReaderImageRequest(
        chapterIndex = 0,
        chapterUrl = "https://books.example/book-a/chapter-1",
        image = image(),
        renderRevision = "r1",
        managedBubble = false
    )

    @Test fun `only the owning registry accepts its registered chapter metadata`() {
        val first = TextReaderImageRegistry()
        val second = TextReaderImageRegistry()
        val a = request()
        val b = a.copy(chapterUrl = "https://books.example/book-b/chapter-1")
        val firstMap = first.register(listOf(a))
        val secondMap = second.register(listOf(b))
        val firstPath = firstMap.keys.single()
        val secondPath = secondMap.keys.single()
        assertNotEquals(firstPath, secondPath)
        assertSame(a, first.lookup(firstPath))
        assertSame(b, second.lookup(secondPath))
        assertNull(first.lookup(secondPath))
        assertNull(second.lookup(firstPath))
        assertNull(first.lookup("text-image/0/arbitrary-encoded-source"))
        assertNull(first.lookup("$firstPath/state"))
        first.close()
        assertNull(first.lookup(firstPath))
        assertSame(b, second.lookup(secondPath))
        second.close()
    }

    @Test fun `repeated preparation shares a live request across chapter ownership maps`() {
        TextReaderImageRegistry().use { registry ->
            val original = request()
            val firstMap = registry.register(listOf(original))
            val secondMap = registry.register(listOf(original.copy()))
            val path = firstMap.keys.single()
            assertSame(original, firstMap[path])
            assertSame(original, secondMap[path])
            assertSame(original, registry.lookup(path))
            assertEquals(firstMap.keys, secondMap.keys)
        }
    }

    @Test fun `chapter index chapter URL and render revision distinguish registrations`() {
        TextReaderImageRegistry().use { registry ->
            val base = request()
            val requests = listOf(
                base,
                base.copy(chapterIndex = 1),
                base.copy(chapterUrl = base.chapterUrl + "-replacement"),
                base.copy(renderRevision = "r2")
            )
            val registered = registry.register(requests)
            assertEquals(requests.size, registered.size)
            for ((path, metadata) in registered) {
                assertTrue(path.startsWith("text-image/${metadata.chapterIndex}/${metadata.renderRevision}/"))
                assertSame(metadata, registry.lookup(path))
            }
        }
    }

    @Test fun `large SVG and scripts never become resource URL payloads`() {
        TextReaderImageRegistry().use { registry ->
            val payload = "private-svg-and-script-value".repeat(45_000)
            val metadata = request().copy(image = image().copy(
                source = "data:image/svg+xml,<svg><text>$payload</text></svg>",
                renderSource = "placeholder,{\"js\":\"$payload\"}",
                click = payload
            ))
            val registered = registry.register(listOf(metadata))
            val path = registered.keys.single()
            assertTrue(metadata.image.source.length > 65_536)
            assertTrue(path.length < 192)
            assertTrue(Regex("text-image/0/r1/image-0-[0-9a-f]{16}").matches(path))
            assertFalse(path.contains("private"))
            assertFalse(path.contains("data:"))
            assertFalse(path.contains("%"))
            assertSame(metadata, registry.lookup(path))
        }
    }

    @Test fun `same render URL preserves independent source click and presentation identity`() {
        TextReaderImageRegistry().use { registry ->
            val base = request()
            val original = base.image
            val images = listOf(
                original,
                original.copy(source = original.source + ",{\"js\":\"differentSource()\"}"),
                original.copy(click = "java.toast('book-b')"),
                original.copy(inline = false),
                original.copy(width = "2em"),
                original.copy(height = "2em"),
                original.copy(alignment = "right"),
                original.copy(sourceStyle = "center")
            )
            assertEquals(1, images.map { it.renderSource }.distinct().size)
            val requests = images.map { base.copy(image = it) } + base.copy(managedBubble = true)
            val registered = registry.register(requests)
            assertEquals(requests.size, registered.size)
            assertEquals(requests.toSet(), registered.values.toSet())
            for ((path, metadata) in registered) assertSame(metadata, registry.lookup(path))
        }
    }

    @Test fun `null empty and embedded separators cannot merge metadata fingerprints`() {
        TextReaderImageRegistry().use { registry ->
            val base = request()
            val requests = listOf(
                base.copy(image = base.image.copy(source = "a\u0000b", renderSource = "c", click = null)),
                base.copy(image = base.image.copy(source = "a", renderSource = "b\u0000c", click = null)),
                base.copy(image = base.image.copy(source = "a", renderSource = "b\u0000c", click = ""))
            )
            assertEquals(requests.size, registry.register(requests).size)
        }
    }

    @Test fun `unsafe path components reject the whole registration batch`() {
        TextReaderImageRegistry().use { registry ->
            val valid = request()
            val knownPath = TextReaderImageRegistry().use { other -> other.register(listOf(valid)).keys.single() }
            val invalid = listOf(
                valid.copy(chapterIndex = -1),
                valid.copy(renderRevision = "../r1"),
                valid.copy(renderRevision = "r1/other"),
                valid.copy(renderRevision = ""),
                valid.copy(renderRevision = "r".repeat(65)),
                valid.copy(image = valid.image.copy(id = "image/0")),
                valid.copy(image = valid.image.copy(id = "image%2f0"))
            )
            for (metadata in invalid) {
                assertThrows(IllegalArgumentException::class.java) {
                    registry.register(listOf(valid, metadata))
                }
                assertNull(registry.lookup(knownPath))
            }
        }
    }

    @Test fun `close invalidates lookups and prevents even empty registrations`() {
        val registry = TextReaderImageRegistry()
        val metadata = request()
        val registered = registry.register(listOf(metadata))
        val path = registered.keys.single()
        registry.close()
        registry.close()
        assertSame(metadata, registered[path])
        assertNull(registry.lookup(path))
        assertThrows(IllegalStateException::class.java) { registry.register(emptyList()) }
        assertThrows(IllegalStateException::class.java) { registry.register(listOf(metadata)) }
        assertNull(registry.lookup(path))
    }

    @Test fun `concurrent close cannot be undone by an in flight registration`() {
        val registry = TextReaderImageRegistry()
        val original = request()
        val registered = registry.register(listOf(original))
        val path = registered.keys.single()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val preparing = executor.submit {
                started.countDown()
                repeat(40) {
                    try {
                        registry.register(listOf(original.copy(renderRevision = "r$it")))
                    } catch (_: IllegalStateException) {
                        return@submit
                    }
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            registry.close()
            preparing.get(3, TimeUnit.SECONDS)
            assertNull(registry.lookup(path))
            assertThrows(IllegalStateException::class.java) { registry.register(listOf(original)) }
        } finally {
            registry.close()
            executor.shutdownNow()
        }
    }
}
