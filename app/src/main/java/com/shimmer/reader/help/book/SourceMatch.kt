package com.shimmer.reader.help.book

/**
 * 换源时判断「这条搜索结果是不是手里这本」。
 *
 * 以前这里是逐字比较：书名必须完全相同，勾了「校验作者」还要求候选源的作者包含原书作者。
 * 实际用起来会漏掉很多能用的源：
 *
 * - 站点喜欢往书名后面挂东西，「斗破苍穹(全本)」「斗破苍穹 最新章节」「斗破苍穹【精校版】」，
 *   逐字比就全被丢掉；
 * - 反过来，手里这本是从某个站加进来的，书名本身就带着这些尾巴，
 *   拿去搜索时关键词是脏的，源站根本搜不出来；
 * - 不少站点压根不填作者，勾了作者校验就整条丢弃，而更新的往往正是这几家。
 *
 * 所以规则改成两条：
 * - 书名按「剥掉站点装饰后是否相同」判断，装饰指括号补充、常见后缀词、空白与标点；
 * - 作者只在两边都写了、而且明显不是一个人时才拦，一边没写就放行。
 *
 * 这里不做「像不像同一本书」的猜测——那是 [IntroMatch] 的事，它只在列表上加一行提示，不拦。
 */
object SourceMatch {

    /** 书名里可能混进搜索高亮，`<em>斗破</em>苍穹`。 */
    private val HTML_TAG = Regex("<[^>]{1,80}>")

    /** 括号补充：「斗破苍穹(全本)」「斗破苍穹【精校版】」「斗破苍穹〔修订〕」。 */
    private val BRACKET = Regex("[（(【\\[〔『][^）)】\\]〕』]*[）)】\\]〕』]")

    /**
     * 站点往书名后面挂的常见尾巴。长的排前面——
     * 否则「全文阅读」会先被当成「阅读」剥掉，剩下的「全文」还在。
     */
    private val TAIL_WORDS = arrayOf(
        "最新章节列表",
        "最新章节更新时间",
        "最新章节",
        "无弹窗广告",
        "无弹窗",
        "全文阅读",
        "免费阅读",
        "在线阅读",
        "手机阅读",
        "免费下载",
        "txt下载",
        "全集下载",
        "全套下载",
        "电子版",
        "电子书",
        "精校版",
        "校对版",
        "手打版",
        "修订版",
        "完整版",
        "未删减版",
        "已完结",
        "完结",
        "全本",
        "全集"
    )

    /** 作者名前后的「作者：」「著」「原著」这类字。 */
    private val AUTHOR_NOISE = Regex(
        "^\\s*作\\s*者[:：\\s]+|[\\s\\[（(【]*(著|编著|原著|原作)[\\]）)】]*\\s*$"
    )

    /**
     * 原名之外多出多少位数字还算同一本。
     *
     * 分卷常写成「斗破苍穹2」「斗破苍穹 3」，这种要能匹配上；
     * 多出实词的就不算（「斗破苍穹」和「斗破苍穹之药老传奇」是两本）。
     */
    private const val NAME_TAIL_DIGITS = 2

    /**
     * 拿书名当搜索关键词前先洗一遍。
     *
     * 手里这本叫「斗破苍穹(全本)」的话，直接拿去搜是搜不到的，得先洗成「斗破苍穹」。
     * 洗完为空（书名整个就是装饰）就退回原名，宁可搜不到也不用空串去搜。
     */
    fun searchKey(raw: String?): String {
        val cleaned = cleanTitle(raw)
        return cleaned.ifEmpty { raw?.trim().orEmpty() }
    }

    /** 去掉标签、括号补充和站点后缀，保留空格与大小写，用于展示与搜索。 */
    fun cleanTitle(raw: String?): String {
        val text = raw?.replace(HTML_TAG, " ")?.trim().orEmpty()
        if (text.isEmpty()) return ""
        var s = text.replace(BRACKET, " ")
            .replace("《", " ")
            .replace("》", " ")
            .replace('\u3000', ' ')
            .replace('\u00A0', ' ')
            .trim()
        var stripped = true
        while (stripped) {
            stripped = false
            for (word in TAIL_WORDS) {
                // 剥完至少要剩两个字，免得把书名整个吃掉
                if (s.length - word.length >= 2 && s.endsWith(word, ignoreCase = true)) {
                    s = s.dropLast(word.length).trim()
                    stripped = true
                    break
                }
            }
        }
        return s
    }

    /** 只留文字和数字，统一小写。「斗破 苍穹(全本)」和「斗破苍穹」在这里变成同一个串。 */
    fun normalizeName(raw: String?): String {
        val s = cleanTitle(raw)
        if (s.isEmpty()) return ""
        return buildString(s.length) {
            for (c in s) {
                if (c.isLetterOrDigit()) append(c.lowercaseChar())
            }
        }
    }

    /**
     * 书名匹配强度：2 = 规范化后完全相同，1 = 只差一到两位数字（分卷），0 = 不算同一本。
     *
     * 换源列表按这个强度排序。「斗破苍穹」搜出来一堆「斗破苍穹2」「斗破苍穹3」时，
     * 同名的那几条必须排在前面，分卷只能垫底——它们确实可能是同一本，但不该占据列表顶部。
     */
    fun nameMatchLevel(base: String?, candidate: String?): Int {
        val a = normalizeName(base)
        val b = normalizeName(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 2
        // 分卷：「斗破苍穹」对「斗破苍穹2」
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (shorter.length < 2 || !longer.startsWith(shorter)) return 0
        val tail = longer.substring(shorter.length)
        return if (tail.length <= NAME_TAIL_DIGITS && tail.all { it.isDigit() }) 1 else 0
    }

    /**
     * 是不是同一个书名。
     *
     * 空名一律不匹配——搜索关键词是空的时候原来也是搜不出东西的，保持原样。
     */
    fun nameMatches(base: String?, candidate: String?): Boolean {
        return nameMatchLevel(base, candidate) > 0
    }

    /** 作者名去掉「作者：」「著」和标点，方便比较。 */
    fun normalizeAuthor(raw: String?): String {
        val s = raw?.replace(AUTHOR_NOISE, "")?.trim().orEmpty()
        if (s.isEmpty()) return ""
        return buildString(s.length) {
            for (c in s) {
                if (c.isLetterOrDigit()) append(c.lowercaseChar())
            }
        }
    }

    /**
     * 作者是不是对得上。
     *
     * 关键是「一边没写就放行」：源站没填作者，不代表不是这本书；
     * 拦掉它，用户就少一个能用的源，而这恰恰是抓不到作者的站点最常见的情况。
     * 两边都写了才比，互相包含也算——「天蚕土豆」对「天蚕土豆 著」这种写法差异很常见。
     */
    fun authorMatches(base: String?, candidate: String?): Boolean {
        val a = normalizeAuthor(base)
        val b = normalizeAuthor(candidate)
        if (a.isEmpty() || b.isEmpty()) return true
        if (a == b) return true
        if (a.length >= 2 && b.contains(a)) return true
        if (b.length >= 2 && a.contains(b)) return true
        return false
    }
}
