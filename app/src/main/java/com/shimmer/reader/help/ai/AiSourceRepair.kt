package com.shimmer.reader.help.ai

import android.content.Context
import android.content.Intent
import com.shimmer.reader.data.entities.BookSource
import com.shimmer.reader.ui.main.ai.AiChatActivity

/**
 * 「让 AI 去修这个书源」。
 *
 * 工具链本来就有：读书源、抓网页、改规则、调试、落库。缺的是一个入口——
 * 用户在校验结果里看到某个源挂了，还得自己跑到 AI 对话里把书名、地址、报错讲一遍。
 * 这里把这几样拼成一段任务描述，直接开会话自动发出去，AI 按固定流程自己跑。
 */
object AiSourceRepair {

    fun launch(context: Context, source: BookSource, failure: String? = null) {
        context.startActivity(Intent(context, AiChatActivity::class.java).apply {
            putExtra(AiChatActivity.EXTRA_INITIAL_PROMPT, buildPrompt(source, failure))
        })
    }

    /**
     * 任务描述写得越死，AI 越不会乱改。
     *
     * 重点是让它先看页面再动手，一次只改一处、改完立刻验证——书源规则之间互相牵连，
     * 一口气重写整个 ruleSearch 通常只会把还能用的部分也弄坏。
     */
    fun buildPrompt(source: BookSource, failure: String?): String = buildString {
        appendLine("帮我修一下这个书源，直接动手，不用先问我。")
        appendLine()
        appendLine("书源：${source.bookSourceName}")
        appendLine("地址：${source.bookSourceUrl}")
        if (!failure.isNullOrBlank()) {
            appendLine("最近一次校验的报错：$failure")
        }
        appendLine()
        appendLine("按这个顺序做：")
        appendLine("1. 先 get_book_source 读完整 JSON，看清 searchUrl、ruleSearch 和 checkKeyWord。")
        appendLine("2. 用 fetch_source_html 抓一次搜索页（关键词用 checkKeyWord，没有就用「我的」），看站点现在返回什么：路径变了、结构变了，还是上了验证或跳转。")
        appendLine("3. 对照 HTML 找出真正坏掉的那一处，能用 workspace_replace_text 的别整段重写。")
        appendLine("4. 每改一处就用 workspace_debug_book_source 验证，搜索、详情、目录、正文依次过，哪步不过回去改哪步。")
        appendLine("5. 全部通过后 workspace_apply_book_source 保存，保存后再 debug 一次确认。")
        appendLine("6. 站点整个没了（域名失效、要登录、整站变空壳）就停手直说，不要猜选择器，也不要把别的书源的规则套过来。")
        appendLine()
        append("最后用一两句话说明改了什么、验证结果如何。")
    }
}
