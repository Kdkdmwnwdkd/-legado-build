package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.direct.TextReaderReplacementProcessor.Rule
import io.legado.app.model.localBook.epubcore.direct.TextReaderReplacementProcessor.Rules
import io.legado.app.model.localBook.epubcore.direct.TextReaderReplacementProcessor.Source
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TextReaderReplacementProcessorTest {
    private val regexReplace: (Rule, String) -> String = { rule, value -> value.replace(rule.regex, rule.replacement) }

    private fun source(raw: String, title: String = "标题") = Source("book-a", "chapter-1", 1, title, raw)
    private fun literal(id: Long, from: String, to: String) = Rule(id, "规则$id", from, to, false)
    private fun regex(id: Long, from: String = ".*", to: String = "@js:counter()") = Rule(id, "规则$id", from, to, true)
    private fun rules(vararg rule: Rule) = Rules(content = rule.toList())

    @Test fun `raw img attributes and usehtml remain available to replacement rules`() {
        val processor = TextReaderReplacementProcessor()
        val input = source("""<usehtml><p>旧正文<img src='old.png' style='text' click='java.toast(1)'></p></usehtml>""")
        val prepared = processor.prepare(input, rules(
            literal(1, "<img src='old.png'", "<img src='new.png'"),
            literal(2, "<usehtml><p>旧正文", "<usehtml><p>新正文"),
            literal(3, "click='java.toast(1)'", "click='java.toast(2)'")
        ), true, regexReplace)
        assertEquals(listOf("新正文"), prepared.content.paragraphs(false))
        assertEquals("new.png", prepared.content.images().single().source)
        assertEquals("java.toast(2)", prepared.content.imageActions().values.single().click)
        val html = prepared.content.html(true) { it }
        assertFalse(html.contains("java.toast"))
        assertTrue(Jsoup.parse(html).select("[click],[onclick],script").isEmpty())
        assertEquals(setOf(1L, 2L, 3L), prepared.effectiveContentRuleIds)
        assertTrue(input.raw.contains("old.png"))
    }

    @Test fun `regex can remove an entire raw image without leaving a gap or click action`() {
        val processor = TextReaderReplacementProcessor()
        val input = source("<p>前<img src='icon.png' style='text' click='java.toast(1)'>后</p><p>尾段</p>")
        val prepared = processor.prepare(input, rules(regex(1, "<img\\b[^>]*>", "")), true, regexReplace)
        assertEquals(listOf("前后", "尾段"), prepared.content.paragraphs(false))
        assertTrue(prepared.content.images().isEmpty())
        assertTrue(prepared.content.imageActions().isEmpty())
        val dom = Jsoup.parse(prepared.content.html(true) { error("Removed image must not request a resource") })
        assertTrue(dom.select("img,figure,[data-legado-image-action],[click],[onclick]").isEmpty())
        assertEquals(listOf("前后", "尾段"), dom.select("p").map { it.text() })
        assertTrue(input.raw.contains("<img"))
    }

    @Test fun `ordered replacements always start from raw input and disabling restores it`() {
        val processor = TextReaderReplacementProcessor()
        val input = source("甲")
        val selected = rules(literal(1, "甲", "甲甲"), literal(2, "甲", "乙"))
        val first = processor.prepare(input, selected, true, regexReplace)
        assertEquals(listOf("乙乙"), first.content.paragraphs(false))
        assertSame(first, processor.prepare(input, selected, true, regexReplace))
        assertEquals(listOf("甲"), processor.prepare(input, selected, false, regexReplace).content.paragraphs(false))
        assertEquals(listOf("乙乙"), processor.prepare(input, selected, true, regexReplace).content.paragraphs(false))
        val edited = rules(literal(1, "甲", "丙"), literal(2, "丙", "丁").copy(enabled = false))
        assertEquals(listOf("丙"), processor.prepare(input, edited, true, regexReplace).content.paragraphs(false))
        assertEquals("甲", input.raw)
    }

    @Test fun `content changes to an original leading title cannot create a second heading`() {
        val processor = TextReaderReplacementProcessor()
        val selected = rules(literal(1, "旧标题", "正文规则改写"))
        for (raw in listOf("\uFEFF旧标题\r\n实际正文", "<h2>旧标题</h2><p>实际正文</p>")) {
            val prepared = processor.prepare(source(raw, "旧标题"), selected, true, regexReplace)
            assertEquals(listOf("旧标题", "实际正文"), prepared.content.paragraphs(true))
        }
    }

    @Test fun `source title is removed once even when the next real paragraph has the same text`() {
        val processor = TextReaderReplacementProcessor()
        val selected = Rules(title = listOf(literal(1, "旧标题", "新标题")), content = listOf(literal(2, "旧标题", "第二段")))
        val prepared = processor.prepare(source("旧标题\n旧标题\n尾段", "旧标题"), selected, true, regexReplace)
        assertEquals(listOf("新标题", "第二段", "尾段"), prepared.content.paragraphs(true))
        val unchanged = processor.prepare(source("旧标题\n旧标题\n尾段", "旧标题"), Rules(), true, regexReplace)
        assertEquals(listOf("旧标题", "旧标题", "尾段"), unchanged.content.paragraphs(true))
    }

    @Test fun `a rule that removes title text cannot delete the first real body paragraph`() {
        val processor = TextReaderReplacementProcessor()
        val selected = rules(literal(1, "旧标题", ""))
        val prepared = processor.prepare(source("旧标题\n正文保留", "旧标题"), selected, true, regexReplace)
        assertEquals(listOf("旧标题", "正文保留"), prepared.content.paragraphs(true))
    }

    @Test fun `title matching after purification never removes an inline image action`() {
        val processor = TextReaderReplacementProcessor()
        val selected = Rules(title = listOf(literal(1, "旧标题", "新标题")))
        val input = source("<p>新标题<img src='icon.svg' style='text' click='java.toast(1)'></p>", "旧标题")
        val prepared = processor.prepare(input, selected, true, regexReplace)
        assertEquals(listOf("新标题", "新标题"), prepared.content.paragraphs(true))
        assertEquals(1, prepared.content.imageActions().size)
        val simple = processor.prepare(source("<p>新标题</p><p>正文</p>", "旧标题"), selected, true, regexReplace)
        assertEquals(listOf("新标题", "正文"), simple.content.paragraphs(true))
    }

    @Test fun `rendered title and unicode offsets use the same purified text as read aloud`() {
        val processor = TextReaderReplacementProcessor()
        val prepared = processor.prepare(source("<p>旧标题</p><p>旧字🌅</p><img src='x.png'><p>结尾</p>", "旧标题"),
            Rules(title = listOf(literal(1, "旧", "新")), content = listOf(literal(2, "旧字", "新内容"))), true, regexReplace)
        assertEquals("新标题\n新内容🌅\n结尾\n", prepared.content.plainText(true))
        for (includeTitle in listOf(true, false)) {
            val spoken = prepared.content.plainText(includeTitle)
            val dom = Jsoup.parse(prepared.content.html(includeTitle) { it })
            dom.select("[data-legado-text-offset]").forEach { block ->
                val offset = block.attr("data-legado-text-offset").toInt()
                assertEquals(block.wholeText(), spoken.substring(offset, offset + block.wholeText().length))
            }
        }
    }

    @Test fun `a failing rule preserves the prior value and later rules still run`() {
        val processor = TextReaderReplacementProcessor()
        val errors = mutableListOf<Long>()
        val selected = rules(literal(1, "甲", "乙"), regex(2), literal(3, "乙", "丙"))
        val prepared = processor.prepare(source("甲"), selected, true,
            replaceRegex = { _, _ -> throw IllegalArgumentException("bad replacement") },
            onRuleError = { rule, _ -> errors.add(rule.id) })
        assertEquals(listOf("丙"), prepared.content.paragraphs(false))
        assertEquals(listOf(2L), errors)
        assertEquals(setOf(1L, 3L), prepared.effectiveContentRuleIds)
    }

    @Test fun `invalid user regex is isolated from later chapters and books`() {
        val processor = TextReaderReplacementProcessor()
        val selected = rules(regex(1, from = "["), literal(2, "旧", "新"))
        val errors = mutableListOf<Long>()
        for (input in listOf(source("旧正文"), source("旧章节").copy(chapterUrl = "chapter-2"),
            source("旧书").copy(bookUrl = "book-b"))) {
            val prepared = processor.prepare(input, selected, true, regexReplace,
                onRuleError = { rule, _ -> errors.add(rule.id) })
            assertEquals(listOf(input.raw.replace("旧", "新")), prepared.content.paragraphs(false))
        }
        assertEquals(listOf(1L, 1L, 1L), errors)
    }

    @Test fun `cancellation propagates without being reported or cached as content`() {
        val processor = TextReaderReplacementProcessor()
        val calls = AtomicInteger()
        val input = source("原文")
        val selected = rules(regex(1))
        assertCancelled {
            processor.prepare(input, selected, true, replaceRegex = { _, _ ->
                calls.incrementAndGet()
                throw CancellationException("cancelled preload")
            }, onRuleError = { _, _ -> fail("Cancellation is not a replacement failure") })
        }
        val prepared = processor.prepare(input, selected, true, replaceRegex = { _, _ ->
            calls.incrementAndGet()
            "新正文"
        })
        assertEquals(listOf("新正文"), prepared.content.paragraphs(false))
        assertEquals(2, calls.get())
    }

    @Test fun `the cache separates books chapters raw text and edited rule bodies`() {
        val processor = TextReaderReplacementProcessor()
        val calls = AtomicInteger()
        val replace: (Rule, String) -> String = { _, _ -> "正文${calls.incrementAndGet()}" }
        val input = source("原文")
        val selected = rules(regex(1))
        processor.prepare(input, selected, true, replace)
        processor.prepare(input.copy(bookUrl = "book-b"), selected, true, replace)
        processor.prepare(input.copy(chapterUrl = "chapter-2", chapterIndex = 2), selected, true, replace)
        processor.prepare(input.copy(raw = "新原文"), selected, true, replace)
        processor.prepare(input, rules(regex(1, to = "@js:edited()")), true, replace)
        assertEquals(5, calls.get())
    }

    @Test fun `both count and character budgets limit cached results`() {
        val calls = AtomicInteger()
        val replace: (Rule, String) -> String = { _, text -> calls.incrementAndGet(); text }
        val selected = rules(regex(1))
        val processor = TextReaderReplacementProcessor(maxEntries = 1)
        processor.prepare(source("甲"), selected, true, replace)
        processor.prepare(source("乙"), selected, true, replace)
        processor.prepare(source("甲"), selected, true, replace)
        assertEquals(3, calls.get())
        val small = TextReaderReplacementProcessor(maxCachedChars = 8)
        repeat(2) { small.prepare(source("123456789"), selected, true, replace) }
        assertEquals(5, calls.get())
    }

    @Test fun `simultaneous html and read aloud requests evaluate a stateful replacement once`() {
        val processor = TextReaderReplacementProcessor()
        val pool = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val followerStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val replace: (Rule, String) -> String = { rule, _ ->
            assertEquals("@js:counter()", rule.replacement)
            val count = calls.incrementAndGet()
            started.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "结果$count"
        }
        try {
            val first = pool.submit<TextReaderReplacementProcessor.Prepared> {
                processor.prepare(source("原文"), rules(regex(1)), true, replace)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit<TextReaderReplacementProcessor.Prepared> {
                processor.prepare(source("原文"), rules(regex(1)), true, replace,
                    checkActive = { followerStarted.countDown() })
            }
            assertTrue(followerStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `invalidating during a load keeps its late result out of the new cache`() {
        val processor = TextReaderReplacementProcessor()
        val pool = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val input = source("原文")
        val selected = rules(regex(1))
        try {
            val old = pool.submit<TextReaderReplacementProcessor.Prepared> {
                processor.prepare(input, selected, true, replaceRegex = { _, _ ->
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    "旧结果"
                })
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            processor.invalidate()
            val fresh = processor.prepare(input, selected, true, replaceRegex = { _, _ -> "新结果" })
            release.countDown()
            assertEquals(listOf("旧结果"), old.get(5, TimeUnit.SECONDS).content.paragraphs(false))
            assertSame(fresh, processor.prepare(input, selected, true, replaceRegex = { _, _ -> error("Must use new cache") }))
            assertEquals(listOf("新结果"), fresh.content.paragraphs(false))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `a new rule revision cancels both obsolete owner and follower without caching them`() {
        val processor = TextReaderReplacementProcessor()
        val pool = Executors.newFixedThreadPool(2)
        val revision = AtomicInteger(1)
        val started = CountDownLatch(1)
        val followerWaiting = CountDownLatch(1)
        val followerChecks = AtomicInteger()
        val release = CountDownLatch(1)
        val checkCurrent = { if (revision.get() != 1) throw CancellationException("Rules changed") }
        val input = source("原文")
        val selected = rules(regex(1))
        try {
            val owner = pool.submit<TextReaderReplacementProcessor.Prepared> {
                processor.prepare(input, selected, true, replaceRegex = { _, _ ->
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    "旧正文"
                }, checkActive = checkCurrent)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val follower = pool.submit<TextReaderReplacementProcessor.Prepared> {
                processor.prepare(input, selected, true, replaceRegex = { _, _ -> "不应发布" }, checkActive = {
                    if (followerChecks.incrementAndGet() > 1) followerWaiting.countDown()
                    checkCurrent()
                })
            }
            assertTrue(followerWaiting.await(5, TimeUnit.SECONDS))
            revision.incrementAndGet()
            processor.invalidate()
            release.countDown()
            assertFutureCancelled(owner)
            assertFutureCancelled(follower)
            val fresh = processor.prepare(input, selected, true, replaceRegex = { _, _ -> "新正文" })
            assertEquals(listOf("新正文"), fresh.content.paragraphs(false))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `an active follower retries after a shared preload was cancelled`() {
        val processor = TextReaderReplacementProcessor()
        val pool = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val followerWaiting = CountDownLatch(1)
        val followerChecks = AtomicInteger()
        val release = CountDownLatch(1)
        try {
            val owner = pool.submit<TextReaderReplacementProcessor.Prepared> {
                processor.prepare(source("原文"), rules(regex(1)), true, replaceRegex = { _, _ ->
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    throw CancellationException("Preload cancelled")
                })
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val follower = pool.submit<TextReaderReplacementProcessor.Prepared> {
                processor.prepare(source("原文"), rules(regex(1)), true, replaceRegex = { _, _ -> "可见正文" }, checkActive = {
                    if (followerChecks.incrementAndGet() > 1) followerWaiting.countDown()
                })
            }
            assertTrue(followerWaiting.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertFutureCancelled(owner)
            assertEquals(listOf("可见正文"), follower.get(5, TimeUnit.SECONDS).content.paragraphs(false))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun `oversized inputs are rejected before rules and oversized outputs preserve the prior body`() {
        val processor = TextReaderReplacementProcessor()
        val calls = AtomicInteger()
        try {
            processor.prepare(source("x".repeat(TextReaderDocument.MAX_SOURCE_CHARS + 1)), rules(regex(1)), true,
                replaceRegex = { _, value -> calls.incrementAndGet(); value })
            fail("Oversized input accepted")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, calls.get())
        val errors = mutableListOf<Long>()
        val prepared = processor.prepare(source("原文"), rules(regex(1), literal(2, "原文", "保留正文")), true,
            replaceRegex = { _, _ -> "x".repeat(TextReaderDocument.MAX_SOURCE_CHARS + 1) },
            onRuleError = { rule, _ -> errors.add(rule.id) })
        assertEquals(listOf("保留正文"), prepared.content.paragraphs(false))
        assertEquals(listOf(1L), errors)
    }

    private fun assertCancelled(block: () -> Unit) {
        try {
            block()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    private fun assertFutureCancelled(future: Future<*>) {
        try {
            future.get(5, TimeUnit.SECONDS)
            fail("Expected cancelled computation")
        } catch (error: ExecutionException) {
            assertTrue(error.cause is CancellationException)
        }
    }
}
