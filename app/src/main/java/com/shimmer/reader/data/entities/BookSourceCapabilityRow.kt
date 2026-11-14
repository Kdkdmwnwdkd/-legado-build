package com.shimmer.reader.data.entities

/**
 * 书源能力快照：只取判断「有没有搜索/发现/详情/目录/正文」所需的字段。
 *
 * 不放进 BookSourcePart 视图，是为了不改数据库 schema——
 * 加一列要提升版本号并补 schema，代价远大于一条只读查询。
 */
data class BookSourceCapabilityRow(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    val respondTime: Long = 180_000L,
    val hasSearch: Boolean = false,
    val hasExplore: Boolean = false,
    val hasInfo: Boolean = false,
    val hasToc: Boolean = false,
    val hasContent: Boolean = false,
)
