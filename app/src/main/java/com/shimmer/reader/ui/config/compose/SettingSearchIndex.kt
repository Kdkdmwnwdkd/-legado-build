package com.shimmer.reader.ui.config.compose

import android.content.Context
import com.shimmer.reader.ui.config.ConfigPageRegistry

/**
 * 一条可被搜到的设置项。
 *
 * 索引直接从各设置页的 [SettingPageSpec] 生成，而不是解析遗留的 preference XML，
 * 这样页面上新增、改名、隐藏的条目会立刻反映到搜索结果里。
 */
data class SettingSearchEntry(
    val pageConfigTag: String,
    val pageTitle: String,
    val sectionTitle: String,
    val key: String,
    val title: String,
    val summary: String,
    val keywords: List<String>
) {
    /** 已小写化的检索文本，包含标题、摘要、key 与页面别名。 */
    val searchText: String = buildString {
        append(title)
        append(' ')
        append(summary)
        append(' ')
        append(key)
        append(' ')
        append(sectionTitle)
        keywords.forEach {
            append(' ')
            append(it)
        }
    }.lowercase()
}

object SettingSearchIndex {

    /**
     * 逐个实例化设置页并读取其条目列表。
     *
     * 页面实例只用于读取 [SettingPageSpec]，不 attach 到 Activity，读完即丢弃。
     * 单个页面出错不影响其余页面——搜索降级为少几项，而不是整块搜不出来。
     */
    fun build(context: Context): List<SettingSearchEntry> {
        val appContext = context.applicationContext
        return ConfigPageRegistry.pages.flatMap { page ->
            runCatching {
                val fragment = page.fragmentClass.getDeclaredConstructor().newInstance()
                fragment.describeForSearch(appContext, page.tag)
            }.getOrDefault(emptyList())
        }
    }
}
