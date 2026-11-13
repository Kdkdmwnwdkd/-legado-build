package com.shimmer.reader.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 解析缓存的回归测试。
 *
 * 这里只验证"缓存行为"和"规则切分结果"，不触碰任何 Android 类：
 * 单测环境没有 TextUtils 之类的桩实现，走 getString 会直接抛异常。
 */
class AnalyzeRuleCacheTest {

    private fun regexOf(rule: AnalyzeRule, pattern: String): Regex? {
        val method = AnalyzeRule::class.java
            .getDeclaredMethod("compileRegexCache", String::class.java)
        method.isAccessible = true
        return method.invoke(rule, pattern) as Regex?
    }

    @Test
    fun `同一个实例内正则只编译一次`() {
        val rule = AnalyzeRule()
        val first = regexOf(rule, "^\\d{2,4}$")
        val second = regexOf(rule, "^\\d{2,4}$")
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `正则缓存跨实例共享`() {
        val first = regexOf(AnalyzeRule(), "^章节[0-9]+$")
        val second = regexOf(AnalyzeRule(), "^章节[0-9]+$")
        assertNotNull(first)
        assertSame(first, second)
    }

    @Test
    fun `非法正则返回 null 且不抛异常`() {
        val rule = AnalyzeRule()
        assertNull(regexOf(rule, "([a-z"))
        assertNull(regexOf(rule, "([a-z"))
    }

    @Test
    fun `规则切分保持原有分段`() {
        val rule = AnalyzeRule()
        // @js 的匹配是贪婪的，整串只切出一段
        assertEquals(1, rule.splitSourceRule("@js:a@js:b").size)
        // 前缀部分单独成段
        assertEquals(2, rule.splitSourceRule("前缀@js:a").size)
        assertEquals(2, rule.splitSourceRule(".book@js:a").size)
    }
}
