package com.shimmer.reader.help.source

import com.shimmer.reader.data.entities.BookSourceCapabilityRow

/**
 * 同域名书源的静态能力对比。
 *
 * 只读书源里已存的规则字段，不发起任何网络请求：
 * 一个源有没有搜索、有没有发现、正文规则全不全，在导入那一刻就已经定了。
 *
 * 这里是纯逻辑，不依赖 Android，便于单测。
 */
data class SourceCapability(
    val url: String,
    val name: String,
    val group: String? = null,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    val hasSearch: Boolean = false,
    val hasExplore: Boolean = false,
    val hasInfo: Boolean = false,
    val hasToc: Boolean = false,
    val hasContent: Boolean = false,
    val respondTime: Long = DEFAULT_RESPOND_TIME,
) {

    /** 能力位掩码，顺序：搜索 / 发现 / 详情 / 目录 / 正文 */
    val capabilityMask: Int
        get() = (if (hasSearch) SEARCH else 0) or
                (if (hasExplore) EXPLORE else 0) or
                (if (hasInfo) INFO else 0) or
                (if (hasToc) TOC else 0) or
                (if (hasContent) CONTENT else 0)

    /** 有几项能力，用于组内排序 */
    val capabilityCount: Int
        get() = Integer.bitCount(capabilityMask)

    /** 是否测过响应时间（默认值视为没测过） */
    val hasRespondTime: Boolean
        get() = respondTime in 1 until DEFAULT_RESPOND_TIME

    /** 能力是否覆盖另一个源：自己会的对方都会，且至少多会一项 */
    fun covers(other: SourceCapability): Boolean {
        val mine = capabilityMask
        val theirs = other.capabilityMask
        return mine != theirs && (mine and theirs) == theirs
    }

    /** 能力完全相同 */
    fun sameCapability(other: SourceCapability): Boolean =
        capabilityMask == other.capabilityMask

    companion object {
        const val SEARCH = 1
        const val EXPLORE = 1 shl 1
        const val INFO = 1 shl 2
        const val TOC = 1 shl 3
        const val CONTENT = 1 shl 4

        /** 书源表里 respondTime 的默认值，等于没测过 */
        const val DEFAULT_RESPOND_TIME = 180_000L
    }
}

/**
 * 一个域名下的所有书源，已按「能力多的在前、快的在前」排好。
 */
data class SourceDomainGroup(
    val host: String,
    val sources: List<SourceCapability>,
) {
    val size: Int get() = sources.size
    val searchCount: Int get() = sources.count { it.hasSearch }
    val exploreCount: Int get() = sources.count { it.hasExplore }
    val disabledCount: Int get() = sources.count { !it.enabled }

    /** 组内能力最全的几个（并列第一） */
    val best: List<SourceCapability>
        get() {
            val top = sources.firstOrNull()?.capabilityCount ?: return emptyList()
            return sources.takeWhile { it.capabilityCount == top }
        }

    /**
     * 能力被同组其它源完全覆盖、或能力与更快的源相同的那些源。
     * 只是提示，不代表可以删——正文干净程度、更新速度这些矩阵看不出来。
     */
    val overlapped: List<SourceCapability>
        get() {
            if (sources.size < 2) return emptyList()
            val result = ArrayList<SourceCapability>()
            for (i in sources.indices) {
                val self = sources[i]
                val covered = sources.any { other ->
                    other !== self && (
                            other.covers(self) ||
                                    (other.sameCapability(self) &&
                                            faster(other, self))
                            )
                }
                if (covered) result.add(self)
            }
            return result
        }

    /** 同能力时谁更快；都没测过就按名字排，保证结果稳定 */
    private fun faster(a: SourceCapability, b: SourceCapability): Boolean {
        if (a.hasRespondTime != b.hasRespondTime) return a.hasRespondTime
        if (a.hasRespondTime && b.hasRespondTime) {
            if (a.respondTime != b.respondTime) return a.respondTime < b.respondTime
        }
        val byName = a.name.compareTo(b.name)
        return byName < 0 || (byName == 0 && a.url < b.url)
    }

    /** 一行摘要，例如「12 个源 · 搜索 3 · 发现 12 · 重叠 4」 */
    fun summary(): String = buildString {
        append("$size 个源")
        append(" · 搜索 $searchCount")
        append(" · 发现 $exploreCount")
        if (disabledCount > 0) append(" · 停用 $disabledCount")
        val overlap = overlapped.size
        if (overlap > 0) append(" · 重叠 $overlap")
    }
}

object SourceDomainIndex {

    /** 一个域名至少要有几个源才值得单独看 */
    const val MIN_GROUP_SIZE = 2

    /**
     * 按域名归组。
     *
     * @param hostOf 取域名，UI 层传 NetworkUtils::getSubDomainOrNull，
     *               它用 PublicSuffix 把 www./m. 这类子域归到同一个主域。
     */
    fun group(
        sources: List<SourceCapability>,
        hostOf: (String) -> String?,
        onlyDuplicated: Boolean = true,
    ): List<SourceDomainGroup> {
        val buckets = LinkedHashMap<String, MutableList<SourceCapability>>()
        for (source in sources) {
            val host = hostOf(source.url)?.trim()?.lowercase()
            val key = host?.takeIf { it.isNotBlank() } ?: continue
            buckets.getOrPut(key) { ArrayList() }.add(source)
        }
        return buckets.map { (host, list) ->
            SourceDomainGroup(host, list.sortedWith(COMPARATOR))
        }.filter { !onlyDuplicated || it.size >= MIN_GROUP_SIZE }
            .sortedWith(compareByDescending<SourceDomainGroup> { it.size }.thenBy { it.host })
    }

    /** 域名一样的才排一起：能力多的在前，同能力快的在前，再按名字 */
    private val COMPARATOR = Comparator<SourceCapability> { a, b ->
        val byCount = b.capabilityCount.compareTo(a.capabilityCount)
        if (byCount != 0) return@Comparator byCount
        when {
            a.hasRespondTime && b.hasRespondTime -> {
                val byTime = a.respondTime.compareTo(b.respondTime)
                if (byTime != 0) return@Comparator byTime
            }
            a.hasRespondTime != b.hasRespondTime -> {
                return@Comparator if (a.hasRespondTime) -1 else 1
            }
        }
        val byName = a.name.compareTo(b.name)
        if (byName != 0) byName else a.url.compareTo(b.url)
    }
}

/** 数据库里只读出来的能力快照转成对比用的模型 */
fun BookSourceCapabilityRow.toCapability(): SourceCapability = SourceCapability(
    url = bookSourceUrl,
    name = bookSourceName,
    group = bookSourceGroup,
    enabled = enabled,
    enabledExplore = enabledExplore,
    hasSearch = hasSearch,
    hasExplore = hasExplore,
    hasInfo = hasInfo,
    hasToc = hasToc,
    hasContent = hasContent,
    respondTime = respondTime,
)
