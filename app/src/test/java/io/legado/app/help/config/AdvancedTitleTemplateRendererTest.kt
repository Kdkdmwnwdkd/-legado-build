package io.legado.app.help.config

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedTitleTemplateRendererTest {

    @Test
    fun chapterTextIsEscapedBeforeInsertionIntoLottieJson() {
        val template = """{"title":"${'$'}{title}","book":"{{bookName}}"}"""
        val rendered = AdvancedTitleConfig.replaceTemplateVariables(
            template,
            mapOf(
                "title" to "第1章 \"测试\"\\下一行\n结尾",
                "bookName" to "书名\t副标题"
            )
        )

        val root = JsonParser.parseString(rendered).asJsonObject
        assertEquals("第1章 \"测试\"\\下一行\n结尾", root["title"].asString)
        assertEquals("书名\t副标题", root["book"].asString)
    }
}
