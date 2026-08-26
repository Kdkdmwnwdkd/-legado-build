package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ParagraphRuleProcessorSourceIndexTest {

    @Test
    fun keepsLegacyMatchingPriorityForContainmentAndDuplicates() {
        val longAnchor = "0123456789abc"

        assertEquals(
            listOf(-1),
            ParagraphRuleProcessor.mapSourceIndexes(
                listOf(longAnchor),
                listOf("prefix-${longAnchor}-suffix", longAnchor),
                listOf(-1, 22)
            )
        )
        assertEquals(
            listOf(22, 22),
            ParagraphRuleProcessor.mapSourceIndexes(
                listOf(longAnchor, longAnchor),
                listOf("a different paragraph", longAnchor),
                listOf(11, 22)
            )
        )
    }

    @Test
    fun normalizesImagesTagsAndWhitespaceBeforeMatching() {
        val oldParagraphs = listOf(
            "<b>正文  第一段</b><IMG src=\"ignored\">",
            "短 段落"
        )

        assertEquals(
            listOf(7, 8, -1),
            ParagraphRuleProcessor.mapSourceIndexes(
                listOf("正文\u3000第一段", "<i>短段落</i>", "   "),
                oldParagraphs,
                listOf(7, 8)
            )
        )
    }

    @Test
    fun indexedMatcherIsEquivalentToLegacyGreedyMatcher() {
        repeat(400) { seed ->
            val random = Random(seed)
            val pool = List(12) { randomAnchor(random) }
            val oldParagraphs = List(random.nextInt(0, 25)) {
                decorate(pool.random(random), random)
            }
            val newParagraphs = List(random.nextInt(0, 25)) {
                val anchor = pool.random(random)
                when (random.nextInt(5)) {
                    0 -> decorate(anchor, random)
                    1 -> "prefix${decorate(anchor, random)}suffix"
                    2 -> if (anchor.length > 13) {
                        decorate(anchor.substring(1, anchor.lastIndex), random)
                    } else {
                        decorate(anchor, random)
                    }
                    3 -> randomAnchor(random)
                    else -> "   "
                }
            }
            val oldSourceIndexes = List(random.nextInt(0, oldParagraphs.size + 3)) { index ->
                if (random.nextInt(4) == 0) -1 else index * 10 + 1
            }

            assertEquals(
                "seed=$seed\nnew=$newParagraphs\nold=$oldParagraphs\nsource=$oldSourceIndexes",
                legacyMapSourceIndexes(newParagraphs, oldParagraphs, oldSourceIndexes),
                ParagraphRuleProcessor.mapSourceIndexes(newParagraphs, oldParagraphs, oldSourceIndexes)
            )
        }
    }

    @Test
    fun mapsLargeReorderedChapterWithoutPairwiseScan() {
        val oldParagraphs = List(1_500) { index -> "paragraph-${index.toString().padStart(5, '0')}-unique-content" }
        val newParagraphs = oldParagraphs.asReversed()
        val sourceIndexes = oldParagraphs.indices.toList()

        assertEquals(
            sourceIndexes.asReversed(),
            ParagraphRuleProcessor.mapSourceIndexes(newParagraphs, oldParagraphs, sourceIndexes)
        )
    }

    @Test
    fun oversizedAnchorFallsBackWithoutChangingLegacyPriority() {
        val anchor = "0123456789abc"
        val oversized = "x".repeat(33_000) + anchor

        assertEquals(
            legacyMapSourceIndexes(listOf(anchor), listOf(oversized, anchor), listOf(-1, 9)),
            ParagraphRuleProcessor.mapSourceIndexes(listOf(anchor), listOf(oversized, anchor), listOf(-1, 9))
        )
    }

    private fun legacyMapSourceIndexes(
        newParagraphs: List<String>,
        oldParagraphs: List<String>,
        oldSourceIndexes: List<Int>
    ): List<Int> {
        val used = hashSetOf<Int>()
        return newParagraphs.mapIndexed { index, paragraph ->
            val direct = oldSourceIndexes.getOrElse(index) { -1 }
            if (direct >= 0 && index < oldParagraphs.size && legacySameAnchor(paragraph, oldParagraphs[index])) {
                used.add(index)
                direct
            } else {
                val matchIndex = oldParagraphs.indices.firstOrNull { oldIndex ->
                    oldIndex !in used && legacySameAnchor(paragraph, oldParagraphs[oldIndex])
                }
                if (matchIndex == null) {
                    -1
                } else {
                    used.add(matchIndex)
                    oldSourceIndexes.getOrElse(matchIndex) { -1 }
                }
            }
        }
    }

    private fun legacySameAnchor(left: String, right: String): Boolean {
        val a = legacyNormalize(left)
        val b = legacyNormalize(right)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || (a.length > 12 && b.contains(a)) || (b.length > 12 && a.contains(b))
    }

    private fun legacyNormalize(text: String): String {
        return text
            .replace(Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""\s+"""), "")
            .replace("\u3000", "")
            .trim()
    }

    private fun randomAnchor(random: Random): String {
        val length = random.nextInt(3, 31)
        return buildString(length) {
            repeat(length) {
                append(('a'.code + random.nextInt(8)).toChar())
            }
        }
    }

    private fun decorate(anchor: String, random: Random): String {
        return when (random.nextInt(4)) {
            0 -> anchor
            1 -> "<b>$anchor</b>"
            2 -> anchor.chunked(3).joinToString(" ")
            else -> "<IMG src=\"cover\">$anchor"
        }
    }
}
