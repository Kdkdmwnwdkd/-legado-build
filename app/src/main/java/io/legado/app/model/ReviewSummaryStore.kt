package io.legado.app.model

import java.util.concurrent.ConcurrentHashMap

/**
 * 段评摘要缓存
 *
 * 章节正文加载时顺手请求段评摘要（paragraphIndex -> count），
 * 排版阶段按段落号取用，避免在排版线程里发网络请求。
 */
object ReviewSummaryStore {

    data class Summary(
        val count: Int,
        val dataKey: String,
    )

    private const val MAX_SIZE = 24
    private val cache = ConcurrentHashMap<String, Map<Int, Summary>>()
    private val order = ArrayDeque<String>()

    private fun key(bookUrl: String, chapterIndex: Int) = "$bookUrl\u0000$chapterIndex"

    fun put(bookUrl: String, chapterIndex: Int, items: Map<Int, Summary>) {
        val key = key(bookUrl, chapterIndex)
        if (items.isEmpty()) {
            cache.remove(key)
            order.remove(key)
            return
        }
        cache[key] = items
        order.remove(key)
        order.addLast(key)
        while (order.size > MAX_SIZE) {
            order.removeFirstOrNull()?.let(cache::remove)
        }
    }

    fun get(bookUrl: String, chapterIndex: Int): Map<Int, Summary>? {
        return cache[key(bookUrl, chapterIndex)]
    }

    fun getCount(bookUrl: String, chapterIndex: Int, paragraphNum: Int): Summary? {
        return cache[key(bookUrl, chapterIndex)]?.get(paragraphNum)
    }

    fun clear() {
        cache.clear()
        order.clear()
    }

}
