package com.shimmer.reader.help.book

import kotlin.math.abs

/**
 * 换源时「旧书停在第几章 → 新书目录第几章」的对齐判定。
 *
 * 这段判定原本整块写在 [BookHelp.getDurChapter] 里，只返回一个 Int。
 * 对不上时它会安静地按原序号就近落位——用户换完源读到一半，
 * 才发现人物对不上、进度跳了几十章，而整个过程没有任何提示。
 *
 * 所以把判定抽出来，除了位置还带回「为什么是这个位置」：
 * 是标题对上了、章节号对上了，还是压根没对上只能按序号猜。
 * [BookHelp.getDurChapter] 仍然走这里，定位结果与以前完全一致。
 */
object ChapterAlign {

    /** 标题相似度达标线，沿用原来的 0.96。 */
    private const val TITLE_THRESHOLD = 0.96

    /** 在原序号与按比例换算的位置之间，各向外多找这么多章。 */
    private const val WINDOW = 10

    enum class Reason {
        /** 本来就在开头，没什么可对齐的。 */
        SAME_INDEX,

        /** 按章节标题相似度对上。 */
        TITLE,

        /** 标题不像，但章节序号对上了。 */
        NUMBER,

        /** 都没对上，按原序号就近落位。 */
        FALLBACK,
    }

    /**
     * @param index 最终采用的章节位置
     * @param bestIndex 搜索窗口里最像的那一章（即使没达标），供提示用
     * @param bestSimilarity 与 [bestIndex] 对应的标题相似度
     */
    data class Result(
        val index: Int,
        val reason: Reason,
        val bestIndex: Int,
        val bestSimilarity: Double,
    ) {
        /** 是不是真的对上了。 */
        val matched: Boolean get() = reason == Reason.TITLE || reason == Reason.NUMBER
    }

    /**
     * @param oldIndex 旧书的当前章节序号
     * @param oldTitle 旧书的当前章节标题
     * @param oldTotal 旧书的章节总数，用来估算新目录里的位置；不确定时传 0
     * @param newTitles 新书源的章节标题
     * @param pureName 去掉章节号、括号等修饰，只留标题主体
     * @param chapterNum 从标题里解析章节序号，解析不出返回 -1
     * @param similarity 两段标题的相似度，0..1
     */
    fun align(
        oldIndex: Int,
        oldTitle: String?,
        oldTotal: Int,
        newTitles: List<String>,
        pureName: (String?) -> String,
        chapterNum: (String?) -> Int,
        similarity: (String, String) -> Double,
    ): Result {
        if (oldIndex <= 0) return Result(0, Reason.SAME_INDEX, 0, 0.0)
        if (newTitles.isEmpty()) return Result(oldIndex, Reason.FALLBACK, 0, 0.0)

        val newSize = newTitles.size
        // 新源可能多了卷标题、少了重复章，先按总章数比例估个大致位置，再在附近找。
        val scaled = if (oldTotal <= 0) oldIndex else oldIndex * oldTotal / newSize
        val from = maxOf(0, minOf(oldIndex, scaled) - WINDOW)
        val to = minOf(newSize - 1, maxOf(oldIndex, scaled) + WINDOW)

        val oldName = pureName(oldTitle)
        var bestIndex = 0
        var bestSimilarity = 0.0
        if (oldName.isNotEmpty()) {
            for (i in from..to) {
                val score = similarity(oldName, pureName(newTitles[i]))
                if (score > bestSimilarity) {
                    bestSimilarity = score
                    bestIndex = i
                }
            }
        }
        if (bestSimilarity > TITLE_THRESHOLD) {
            return Result(bestIndex, Reason.TITLE, bestIndex, bestSimilarity)
        }

        val oldNum = chapterNum(oldTitle)
        if (oldNum > 0) {
            var numIndex = 0
            var numValue = 0
            for (i in from..to) {
                val candidate = chapterNum(newTitles[i])
                if (candidate == oldNum) {
                    numIndex = i
                    numValue = candidate
                    break
                }
                if (abs(candidate - oldNum) < abs(numValue - oldNum)) {
                    numIndex = i
                    numValue = candidate
                }
            }
            if (abs(numValue - oldNum) < 1) {
                return Result(numIndex, Reason.NUMBER, bestIndex, bestSimilarity)
            }
        }

        return Result(
            index = minOf(newSize - 1, maxOf(0, oldIndex)),
            reason = Reason.FALLBACK,
            bestIndex = bestIndex,
            bestSimilarity = bestSimilarity,
        )
    }
}
