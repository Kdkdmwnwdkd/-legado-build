package io.legado.app.help.book.highlight

import org.junit.Assert.*
import org.junit.Test

class HighlightMatcherTest {
    @Test fun literalSpecialCharactersAreNotRegularExpressions() {
        val matcher = HighlightMatcher(listOf(HighlightRule(keyword = "[a].")))
        assertEquals(listOf(2 to 6), matcher.matches("x [a]. y").map { it.start to it.end })
    }

    @Test fun bracketMatchesKeepDelimitersAndUtf16Offsets() {
        val rule = HighlightRule(keyword = "「([^」]*)」", isRegex = true)
        val text = "😀「甲乙」尾"
        val found = HighlightMatcher(listOf(rule)).matches(text).single()
        assertEquals(2, found.start)
        assertEquals("「甲乙」", text.substring(found.start, found.end))
    }

    @Test fun overlappingRulesRetainTheirPrecedence() {
        val a = HighlightRule(keyword = "abcd")
        val b = HighlightRule(keyword = "bc")
        assertEquals(listOf(a.id, b.id), HighlightMatcher(listOf(a, b)).matches("abcd").map { it.rule.id })
    }

    @Test fun disabledAndInvalidRulesDoNotBreakValidMatches() {
        val good = HighlightRule(keyword = "hit")
        val rules = listOf(good.copy(id = "disabled", enabled = false), good.copy(keyword = "(", isRegex = true), good)
        assertEquals(listOf(good.id), HighlightMatcher(rules).matches("hit").map { it.rule.id })
    }

    @Test fun emptyKeywordOnlyMatchesAnEntireTitle() {
        val title = HighlightRule(keyword = "", titleOnly = true)
        val matcher = HighlightMatcher(listOf(title))
        assertTrue(matcher.matches("正文").isEmpty())
        assertEquals(2, matcher.matches("标题", true).single().end)
        assertNotNull(HighlightMatcher.validationError(title.copy(titleOnly = false)))
    }

    @Test fun multilineSupportsAnchorsAndNewlines() {
        val rule = HighlightRule(keyword = "^a.*z$", isRegex = true, isMultiline = true)
        assertEquals("a\nz", HighlightMatcher(listOf(rule)).matches("x\na\nz\ny").single().let {
            "x\na\nz\ny".substring(it.start, it.end)
        })
    }

    @Test fun zeroWidthMatchesNeverInsertVisibleOrEmptySpans() {
        assertTrue(HighlightMatcher(listOf(HighlightRule(keyword = "^|$", isRegex = true))).matches("text").isEmpty())
    }

    @Test(timeout = 2000) fun pathologicalRegexHasABoundedCharacterBudget() {
        val rule = HighlightRule(keyword = "(a+)+b", isRegex = true)
        assertTrue(HighlightMatcher(listOf(rule)).matches("a".repeat(30_000)).isEmpty())
    }

    @Test(timeout = 2000) fun ambiguousFailingMatchStopsInsideTheEngine() {
        val rule = HighlightRule(keyword = "^(a|aa)+$", isRegex = true)
        val matcher = HighlightMatcher(listOf(rule))
        assertTrue(matcher.matches("a".repeat(30_000) + "!").isEmpty())
        assertEquals(3, matcher.matches("aaa").single().end)
    }

    @Test fun lookaroundsNamedGroupsAndBackreferencesKeepChineseAndEmojiOffsets() {
        val text = "😀前甲乙甲乙后 😀前甲乙甲乙后"
        val expression = "(?<=前)(?<word>甲乙)\\k<word>(?=后)"
        val matches = HighlightMatcher(listOf(HighlightRule(keyword = expression, isRegex = true))).matches(text)
        assertEquals(listOf(3 to 7, 12 to 16), matches.map { it.start to it.end })
        assertTrue(matches.all { text.substring(it.start, it.end) == "甲乙甲乙" })
    }

    @Test fun regexQuotesUnicodePropertiesAndModernEscapesRemainUsable() {
        for (expression in listOf("\\p{IsHan}+", "\\p{sc=Han}+", "\\p{script=Han}+")) {
            val rule = HighlightRule(keyword = expression, isRegex = true)
            assertNull(HighlightMatcher.validationError(rule))
            assertEquals(listOf(2 to 4), HighlightMatcher(listOf(rule)).matches("😀甲乙a").map { it.start to it.end })
        }
        val quoted = HighlightRule(keyword = "\\Q[a].\\E", isRegex = true)
        assertEquals(listOf(2 to 6), HighlightMatcher(listOf(quoted)).matches("x [a]. y").map { it.start to it.end })
        val newline = HighlightRule(keyword = "甲\\R乙", isRegex = true)
        assertEquals(4, HighlightMatcher(listOf(newline)).matches("甲\r\n乙").single().end)
        val emoji = HighlightRule(keyword = "\\x{1f600}", isRegex = true)
        assertEquals(listOf(1 to 3), HighlightMatcher(listOf(emoji)).matches("甲😀乙").map { it.start to it.end })
    }

    @Test fun propertyAliasesInsideQuotesAndEscapedBackslashesRemainLiteral() {
        for (expression in listOf("\\Q\\p{IsHan}\\E", "\\\\p\\{IsHan\\}")) {
            val rule = HighlightRule(keyword = expression, isRegex = true)
            assertEquals(9, HighlightMatcher(listOf(rule)).matches("\\p{IsHan}").single().end)
        }
    }

    @Test fun emptyMatchesAdvanceOverWholeEmojiAndDoNotLoseFollowingMatches() {
        val rule = HighlightRule(keyword = "(?=😀)|甲", isRegex = true)
        assertEquals(listOf(2 to 3, 5 to 6), HighlightMatcher(listOf(rule)).matches("😀甲😀甲").map { it.start to it.end })
    }

    @Test fun inlineFlagsAndPossessiveCharacterClassIntersectionsArePreserved() {
        val rule = HighlightRule(keyword = "(?im)^[a-z&&[^ab]]++$", isRegex = true)
        assertEquals(listOf(3 to 6), HighlightMatcher(listOf(rule)).matches("ab\nCDE\nfab").map { it.start to it.end })
    }

    @Test fun chapterInitialRuleSupportsItsOriginalVariableLengthLookbehind() {
        val expression = "^(?<=第[0-9零一二三四五六七八九十百千]+章\\s)[^\\s]{1}"
        val rule = HighlightRule(keyword = expression, isRegex = true, isMultiline = true)
        assertNull(HighlightMatcher.validationError(rule))
        val text = "😀\n第十二章\n正文前几个字\n第103章\n再次开始"
        val found = HighlightMatcher(listOf(rule)).matches(text)
        assertEquals(listOf("正", "再"), found.map { text.substring(it.start, it.end) })
    }

    @Test fun chapterTitleLookbehindMatchesTheRequestedInitialsWithoutChangingText() {
        val expression = "^(?<=第[0-9零一二三四五六七八九十百千]+章\\s+.*?\\n\\s*)[^\\s]{2}"
        val rule = HighlightRule(keyword = expression, isRegex = true, isMultiline = true)
        val text = "😀\n第十二章 新的故事\n正文第一段\n下一段"
        val found = HighlightMatcher(listOf(rule)).matches(text)
        assertEquals(listOf("正文", "下一"), found.map { text.substring(it.start, it.end) })
    }

    @Test fun variableLookbehindsDoNotConsumeTextOrSkipOverlappingCandidates() {
        val expressions = listOf("(?<=a+)a", "(?<!a+)a", "(?<=a+)(?<!ba+)a")
        val text = "aaaa baa caa"
        for (expression in expressions) {
            val expected = java.util.regex.Pattern.compile(expression).matcher(text)
            val ranges = buildList { while (expected.find()) add(expected.start() to expected.end()) }
            val rule = HighlightRule(keyword = expression, isRegex = true)
            assertNull(HighlightMatcher.validationError(rule))
            assertEquals(expression, ranges, HighlightMatcher(listOf(rule)).matches(text).map { it.start to it.end })
        }
    }

    @Test fun variableLookbehindAssertionsSeeTheCompleteInput() {
        for (expression in listOf("(?<=a+(?=b))b", "(?<=a+\\b)b", "(?<=a+$)b")) {
            val text = "aaaab aaab"
            val expected = java.util.regex.Pattern.compile(expression).matcher(text)
            val ranges = buildList { while (expected.find()) add(expected.start() to expected.end()) }
            val rule = HighlightRule(keyword = expression, isRegex = true)
            assertNull(HighlightMatcher.validationError(rule))
            assertEquals(expression, ranges, HighlightMatcher(listOf(rule)).matches(text).map { it.start to it.end })
        }
    }

    @Test(timeout = 2000) fun variableLookbehindUsesTheSameExecutionDeadline() {
        val rule = HighlightRule(keyword = "(?<=(?:a|aa)+)b", isRegex = true)
        assertNull(HighlightMatcher.validationError(rule))
        val matcher = HighlightMatcher(listOf(rule))
        assertTrue(matcher.matches("a".repeat(30_000) + "!b").isEmpty())
        assertEquals(listOf(1 to 2), matcher.matches("ab").map { it.start to it.end })
    }

    @Test(timeout = 2000) fun manyCheapFailedStartsCannotBypassTheSearchDeadline() {
        val rule = HighlightRule(keyword = "a{2000}[bc]", isRegex = true)
        val matcher = HighlightMatcher(listOf(rule))
        assertTrue(matcher.matches("a".repeat(200_000)).isEmpty())
        assertEquals(listOf(0 to 2001), matcher.matches("a".repeat(2000) + "b").map { it.start to it.end })
    }

    @Test fun scanningBatchesPreserveCompleteMatchesAndUnicodeBoundaries() {
        val rule = HighlightRule(keyword = "「.*?」", isRegex = true)
        val text = "😀".repeat(8) + "「" + "甲乙".repeat(1000) + "」"
        val found = HighlightMatcher(listOf(rule)).matches(text).single()
        assertEquals(16, found.start)
        assertEquals(text.length, found.end)
        assertEquals("「" + "甲乙".repeat(1000) + "」", text.substring(found.start, found.end))
    }
}
