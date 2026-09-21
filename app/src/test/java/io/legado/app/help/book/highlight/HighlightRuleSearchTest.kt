package io.legado.app.help.book.highlight

import org.junit.Assert.*
import org.junit.Test

class HighlightRuleSearchTest {
    @Test fun findsNamesGroupsAndLiteralExpressionsWithoutChangingOrderOrFlags() {
        val rabbit = HighlightRule(name = "简约白白兔", groupName = "Kitsch", keyword = "“[^”]+”", enabled = false)
        val lotus = HighlightRule(name = "荷花", groupName = "花朵", keyword = "「.+?」")
        val namedByKeyword = HighlightRule(keyword = "另一个白白兔")
        val rules = listOf(lotus, rabbit, namedByKeyword)
        assertEquals(listOf(rabbit, namedByKeyword), HighlightRuleSearch.filter(rules, " 白白兔 "))
        assertEquals(listOf(rabbit), HighlightRuleSearch.filter(rules, "kItScH"))
        assertEquals(listOf(lotus), HighlightRuleSearch.filter(rules, "花朵"))
        assertEquals(listOf(rabbit), HighlightRuleSearch.filter(rules, "[^”]+"))
        assertEquals(listOf(rabbit), HighlightRuleSearch.filter(rules, "["))
        assertEquals(rules, HighlightRuleSearch.filter(rules, "　\n\t"))
        assertTrue(HighlightRuleSearch.filter(rules, "没有这条规则").isEmpty())
        assertFalse(rabbit.enabled)
        assertEquals(listOf(lotus, rabbit, namedByKeyword), rules)
    }
}
