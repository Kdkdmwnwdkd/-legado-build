package io.legado.app.help.ai

/**
 * AI 正文净化：把选中段落交给模型，去掉广告、网址、求票等非正文内容，
 * 修正明显的错字与错位标点，再原样返回清洗后的正文。
 *
 * 只做「选中文本 -> 净化文本」的一次性转换，不改动书籍缓存，
 * 是否替换由调用方决定。
 */
object AiTextPurify {

    /** 单次净化的字数上限，超过则提示分段选择。 */
    const val MAX_INPUT_LENGTH = 6000

    private const val SYSTEM_PROMPT = """
你是中文小说正文清洗器。用户会给你一段从书源抓取的正文，其中可能混入广告、网址、推广语、求票、乱码、重复标点、OCR 错字或章节标题残留。

严格执行以下规则：
1. 只输出清洗后的正文，不要解释，不要输出 Markdown，不要用代码块包裹。
2. 删除广告、公众号/QQ群/网址、求收藏求月票、更新时间、"本章未完""请记住本站"等非正文内容。
3. 修正明显的错别字、多余字、重复字、错位标点和不合理断行；保留原有段落划分与对话格式。
4. 不改写情节，不润色文风，不增删有效内容，不改变人称与叙述顺序。
5. 原文没有需要清洗的内容时，原样返回。
"""

    class PurifyException(message: String) : Exception(message)

    suspend fun purify(text: String): String {
        val source = normalize(text)
        if (source.isBlank()) {
            throw PurifyException("选中内容为空")
        }
        if (source.length > MAX_INPUT_LENGTH) {
            throw PurifyException("选中内容 ${source.length} 字，超过单次上限 $MAX_INPUT_LENGTH 字，请分段净化")
        }
        val raw = AiChatService.completeText(
            systemPrompt = SYSTEM_PROMPT.trimIndent(),
            userText = source
        )
        val cleaned = normalizeModelOutput(raw)
        if (cleaned.isBlank()) {
            throw PurifyException("模型没有返回内容，请稍后重试")
        }
        return cleaned
    }

    fun normalize(text: String): String {
        return text.lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    private fun normalizeModelOutput(text: String): String {
        var output = text.trim()
        if (output.startsWith("```")) {
            output = output.lines()
                .drop(1)
                .dropLastWhile { it.trim().startsWith("```") }
                .joinToString("\n")
                .trim()
        }
        // 模型偶尔会把结果整体包在一对引号里
        if (output.length > 1 && (
                (output.startsWith("\"") && output.endsWith("\"")) ||
                    (output.startsWith("“") && output.endsWith("”"))
                )
        ) {
            output = output.substring(1, output.length - 1).trim()
        }
        return output
    }
}
