package com.shimmer.reader.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroMatchTest {

    private val base = "少年林寒自幼父母双亡，被青云山掌门收养，十六岁那年觉醒剑骨，" +
        "从此踏上一条逆天改命的路。他要在宗门大比上一战成名，也要查清当年灭门惨案的真相。"

    private val sameBookRewritten = "少年林寒自幼父母双亡，被青云山掌门收养，十六岁那年觉醒剑骨，" +
        "从此踏上逆天改命之路。他要在宗门大比上一战成名，也要查清当年灭门惨案的真相。" +
        "最新章节请访问本站，更新最快。"

    private val otherBook = "都市白领苏晚意外得到一款可以预知未来的手机应用，从此在商界翻云覆雨。" +
        "但她渐渐发现，每一次预知都在悄悄消耗自己的寿命，而应用背后的开发者似乎认识她。"

    @Test
    fun `同一本书被改写并加了广告仍然判为匹配`() {
        assertFalse(IntroMatch.isMismatch(base, sameBookRewritten))
    }

    @Test
    fun `两本不同的书判为不匹配`() {
        assertTrue(IntroMatch.isMismatch(base, otherBook))
    }

    @Test
    fun `简介为空时不判断`() {
        assertFalse(IntroMatch.isMismatch(base, null))
        assertFalse(IntroMatch.isMismatch(null, otherBook))
        assertFalse(IntroMatch.isMismatch(base, "   "))
    }

    @Test
    fun `简介太短时不判断`() {
        assertFalse(IntroMatch.isMismatch(base, "暂无简介"))
        assertEquals(IntroMatch.UNKNOWN, IntroMatch.similarity(base, "暂无简介"), 0.0001f)
    }

    @Test
    fun `HTML 标签和空白不影响判断`() {
        val withHtml = "<p>少年林寒自幼父母双亡，<br/>被青云山掌门收养，十六岁那年觉醒剑骨，" +
            "从此踏上一条逆天改命的路。</p><p>他要在宗门大比上一战成名，也要查清当年灭门惨案的真相。</p>"
        assertFalse(IntroMatch.isMismatch(base, withHtml))
    }

    @Test
    fun `有一整段文字完全相同时直接判为匹配`() {
        // 前半段是同一段介绍，后半段被站点换成了完全无关的内容
        val partiallyRewritten = "少年林寒自幼父母双亡，被青云山掌门收养，十六岁那年觉醒剑骨，" +
            "从此踏上一条逆天改命的路。" +
            "本书已完结，共三百二十万字，读者评分九点二分，感谢各位一路支持，新书正在筹备中。"
        assertFalse(IntroMatch.isMismatch(base, partiallyRewritten))
    }

    @Test
    fun `完全相同的简介相似度为 1`() {
        assertEquals(1f, IntroMatch.similarity(base, base), 0.0001f)
    }

    @Test
    fun `标点不同的同一段话仍然高度相似`() {
        val punctuated = "少年林寒自幼父母双亡！被青云山掌门收养；十六岁那年觉醒剑骨，" +
            "从此踏上一条逆天改命的路。他要在宗门大比上一战成名，也要查清当年灭门惨案的真相。"
        assertTrue(IntroMatch.similarity(base, punctuated) > 0.5f)
    }
}
