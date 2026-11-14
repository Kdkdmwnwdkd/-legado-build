package com.shimmer.reader.help.search

import com.shimmer.reader.data.entities.SearchBook

/**
 * 搜索开始前先用本地记录铺一屏，网络结果到达后再逐步替换。
 *
 * 这里只做「把缓存整理成能直接显示的样子」：同一本书在多个书源各有记录，
 * 直接铺开会是一屏重复条目，所以先按书名 + 作者合并，把源数量算出来。
 */
object LocalPreview {

    /** 首屏最多铺多少条，够填满一屏就行，多了反而挡着真实结果 */
    const val DEFAULT_LIMIT = 60

    /**
     * 合并同一本书的多条缓存，并按「源数量、记录时间」排序。
     *
     * 保留 time 最新的那条作为主记录：它的书名、封面、最新章节最接近现在。
     * 其它记录只贡献书源，不覆盖主记录的任何字段。
     */
    fun merge(books: List<SearchBook>, limit: Int = DEFAULT_LIMIT): List<SearchBook> {
        if (books.isEmpty() || limit <= 0) return emptyList()
        val groups = LinkedHashMap<String, MutableList<SearchBook>>()
        books.forEach { book ->
            groups.getOrPut(book.groupKey()) { ArrayList() }.add(book)
        }
        val merged = groups.values.map { group ->
            val keeper = group.maxByOrNull { it.time } ?: group.first()
            if (group.size > 1) {
                group.forEach { other ->
                    if (other.bookUrl != keeper.bookUrl) {
                        keeper.addOrigin(other.origin)
                    }
                }
            }
            keeper
        }
        return merged
            .sortedWith(
                compareByDescending<SearchBook> { it.origins.size }
                    .thenByDescending { it.time }
                    .thenBy { it.name }
            )
            .take(limit)
    }

    /**
     * 合并用的分组键。
     *
     * 作者为空时不参与分组：同一本没有作者名的书在各源之间未必是同一本，
     * 硬合在一起会让用户点进去发现是另一本书。
     */
    private fun SearchBook.groupKey(): String {
        val cleanName = name.trim()
        val cleanAuthor = author.trim()
        return if (cleanAuthor.isEmpty()) "$cleanName\u0000$bookUrl" else "$cleanName\u0000$cleanAuthor"
    }
}
