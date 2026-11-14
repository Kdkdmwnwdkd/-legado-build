package com.shimmer.reader.help.book

import com.shimmer.reader.data.entities.Book

/**
 * 「失效书源清单」顶部的统计。
 *
 * [bookCount] 是受影响的书，[sourceCount] 是这些书落在几个书源上。
 * 两个数分开算：十几本书可能只挂在一个源上，这时候用户该做的是修那个源，
 * 而不是一本一本换。
 */
data class OrphanSourceStats(
    val bookCount: Int,
    val sourceCount: Int
) {
    val isEmpty: Boolean get() = bookCount == 0
}

/**
 * 按 origin 去重数源数量。
 *
 * origin 为空的记录理论上不会出现在查询结果里（origin 是匹配条件），
 * 但列表可能被别处复用，这里仍然过滤掉，免得算出一个空的「第 0 个书源」。
 */
fun orphanSourceStats(books: List<Book>): OrphanSourceStats {
    if (books.isEmpty()) return OrphanSourceStats(0, 0)
    val origins = books.asSequence()
        .map { it.origin }
        .filter { it.isNotBlank() }
        .toSet()
    return OrphanSourceStats(books.size, origins.size)
}
