package com.shimmer.reader.help.book

/**
 * 换源时判断「候选源的简介和原书是不是同一本书」。
 *
 * 换源搜索只能按「书名 + 作者」匹配，同名不同书的情况并不少见——尤其是那些书名很泛的
 * 玄幻、言情。用户点进去换完源，读了几章才发现人物对不上，前面白读了。
 *
 * 这里不追求「判断准确」，只追求「明显不对的时候提醒一句」：
 * - 简介缺失或太短（「暂无简介」），不判断，当作未知，绝不打扰；
 * - 有一段较长的文字完全一样，就认为匹配，哪怕整体被改写、加了广告；
 * - 否则用字符 bigram 的重合率衡量，重合率很低才算「不像」。
 *
 * 判定为「不像」也不会被过滤掉、不改排序、不改用户的赞踩评分，只是在条目上加一行提示。
 */
object IntroMatch {

    /** 低于这个长度就不判断：多半是「暂无简介」，比了只会误报。 */
    private const val MIN_LENGTH = 24

    /** 只比前 300 个字符：简介尾部大多是站点广告，拉长比对反而更不准。 */
    private const val COMPARE_LENGTH = 300

    /** bigram 重合率低于这个值才算「不像」。宁漏勿错，误报比漏报更烦人。 */
    private const val MISMATCH_JACCARD = 0.15f

    /** 这么长的连续片段相同，就认为介绍文案同源，直接判为匹配。 */
    private const val OVERLAP_WINDOW = 12

    /** 无法判断（简介缺失或太短）。 */
    const val UNKNOWN = -1f

    /**
     * 字符 bigram 的 Jaccard 重合率，取值 0..1；无法判断时返回 [UNKNOWN]。
     *
     * 中文按两字滑窗切，比按词切更稳（不用分词，也不怕简介里混着书名号、网址）。
     */
    fun similarity(base: String?, candidate: String?): Float {
        val a = normalize(base) ?: return UNKNOWN
        val b = normalize(candidate) ?: return UNKNOWN
        return jaccard(a, b)
    }

    /**
     * 是否「明显不是同一本书」。无法判断时一律返回 false——没有证据就不提示。
     */
    fun isMismatch(base: String?, candidate: String?): Boolean {
        val a = normalize(base) ?: return false
        val b = normalize(candidate) ?: return false
        if (hasLongOverlap(a, b)) return false
        val score = jaccard(a, b)
        return score >= 0f && score < MISMATCH_JACCARD
    }

    private fun jaccard(a: String, b: String): Float {
        val setA = bigrams(a)
        val setB = bigrams(b)
        if (setA.isEmpty() || setB.isEmpty()) return UNKNOWN
        var intersection = 0
        for (gram in setA) {
            if (gram in setB) intersection++
        }
        val union = setA.size + setB.size - intersection
        if (union == 0) return UNKNOWN
        return intersection.toFloat() / union
    }

    /** 存在 [OVERLAP_WINDOW] 个字符的完全相同的片段。 */
    private fun hasLongOverlap(a: String, b: String): Boolean {
        if (a.length < OVERLAP_WINDOW || b.length < OVERLAP_WINDOW) return false
        val grams = HashSet<String>(a.length)
        for (i in 0..a.length - OVERLAP_WINDOW) {
            grams.add(a.substring(i, i + OVERLAP_WINDOW))
        }
        for (i in 0..b.length - OVERLAP_WINDOW) {
            if (b.substring(i, i + OVERLAP_WINDOW) in grams) return true
        }
        return false
    }

    /**
     * 只留文字和数字，去掉标签、空白和标点，统一小写，再截断。
     *
     * 各站的简介常见 `<br>`、`&nbsp;`、全角空格这些噪声，直接比会把格式差异算成内容差异。
     */
    private fun normalize(text: String?): String? {
        val raw = text?.trim()
        if (raw.isNullOrEmpty()) return null
        val sb = StringBuilder(raw.length)
        var inTag = false
        for (c in raw) {
            when {
                c == '<' -> inTag = true
                c == '>' -> {
                    inTag = false
                    sb.append(' ')
                }

                inTag -> Unit
                c.isLetterOrDigit() -> sb.append(c.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        val compact = sb.toString().replace(" ", "")
        if (compact.length < MIN_LENGTH) return null
        return compact.take(COMPARE_LENGTH)
    }

    private fun bigrams(text: String): HashSet<String> {
        val set = HashSet<String>(text.length)
        for (i in 0 until text.length - 1) {
            set.add(text.substring(i, i + 2))
        }
        return set
    }
}
