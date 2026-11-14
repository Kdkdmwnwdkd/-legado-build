package com.shimmer.reader.help.source

/**
 * 同域名组里「谁留、谁停」的建议。
 *
 * 判断只用两样东西：书源规则里写死的五项能力，以及刚刚测出来的可用性。
 * 停用是破坏性操作，所以这里刻意保守——拿不出「能力不弱、且确实更靠谱」的
 * 替代源就不动它，宁可少停一个，也不要停掉唯一能用的那个。
 */
data class DomainPickPlan(
    val keep: List<SourceCapability> = emptyList(),
    val disable: List<SourceCapability> = emptyList(),
    /** 被停用的 url → 顶替它的源名 */
    val replacedBy: Map<String, String> = emptyMap(),
    /** 被停用的 url → 为什么判断它可以停 */
    val reasons: Map<String, String> = emptyMap(),
) {
    val hasAction: Boolean get() = disable.isNotEmpty()

    val disableNames: String
        get() = disable.joinToString("、") { it.name }
}

/**
 * 组内择优。
 *
 * 顺序：先按「实测通过 → 没测过 → 实测失败」，再按能力数量、实测耗时、记录里的响应时间。
 * 依次往下走，能找到一个已经留在计划里的源顶替它，就把它列进停用。
 *
 * 为什么要「顶替」而不是简单取能力最多的那个：一个组里经常是 A 覆盖 B、B 覆盖 C
 * 的链子，逐条替换能保证停完之后，能力并集一点没少。
 */
object SourceDomainPick {

    /**
     * 只在「确实更差」时给建议：
     * - 对方能力严格包含它（多会一项以上）
     * - 或者能力完全相同，但对方实测明显更快、或它根本没通过
     *
     * 同能力、都没测过、速度也看不出来的，一律不动。
     */
    fun plan(
        group: SourceDomainGroup,
        probes: Map<String, ProbeResult?> = emptyMap(),
    ): DomainPickPlan {
        val candidates = group.sources.filter { it.enabled }
        if (candidates.size < 2) {
            return DomainPickPlan(keep = candidates)
        }
        val ordered = candidates.sortedWith(comparator(probes))
        val keep = ArrayList<SourceCapability>(ordered.size)
        val disable = ArrayList<SourceCapability>()
        val replacedBy = LinkedHashMap<String, String>()
        val reasons = LinkedHashMap<String, String>()
        for (source in ordered) {
            var keeper: SourceCapability? = null
            var reason: String? = null
            for (kept in keep) {
                val why = canReplace(kept, source, probes) ?: continue
                keeper = kept
                reason = why
                break
            }
            val winner = keeper
            if (winner == null) {
                keep.add(source)
            } else {
                disable.add(source)
                replacedBy[source.url] = winner.name
                reasons[source.url] = reason.orEmpty()
            }
        }
        return DomainPickPlan(
            keep = keep,
            disable = disable,
            replacedBy = replacedBy,
            reasons = reasons,
        )
    }

    /**
     * 已保留的 [keeper] 能不能顶替 [target]，能则返回一句理由。
     *
     * 返回 null 表示不能——包括「能力不够」「对方实测没通过」「两边都测过但对方明显更快」。
     */
    private fun canReplace(
        keeper: SourceCapability,
        target: SourceCapability,
        probes: Map<String, ProbeResult?>,
    ): String? {
        val covers = keeper.covers(target)
        if (!covers && !keeper.sameCapability(target)) return null

        val keeperProbe = probes[keeper.url]
        val targetProbe = probes[target.url]
        if (keeperProbe?.passed == false) return null
        if (targetProbe?.passed == false) {
            return if (covers) "能力更全，这里实测没通过" else "同能力，这里实测没通过"
        }

        val keeperPassed = keeperProbe?.passed == true
        val targetPassed = targetProbe?.passed == true
        if (keeperPassed && targetPassed) {
            // 对方快出三倍以上就别动了：多一项能力换几倍等待并不划算
            if (targetProbe != null && targetProbe.elapsedMs > 0 &&
                keeperProbe != null && keeperProbe.elapsedMs > targetProbe.elapsedMs * 3
            ) {
                return null
            }
            return when {
                covers -> "能力更全，实测都能用"
                isClearlyFaster(keeperProbe, targetProbe) -> "同能力，实测更快"
                else -> null
            }
        }

        // 只有一边测过：能力更全的可以顶替，同能力的不拿没测过的去停测过的
        if (covers) return "能力更全"
        if (keeperPassed) return "同能力，这边实测通过"

        // 两边都没测过：看记录里的响应时间，差距要超过两成
        if (keeper.hasRespondTime && target.hasRespondTime &&
            keeper.respondTime * 5 <= target.respondTime * 4
        ) {
            return "同能力，记录的响应更快"
        }
        return null
    }

    /** a 的实测耗时是否明显短于 b（差两成以上），没有耗时数据时按「不算更快」处理 */
    private fun isClearlyFaster(a: ProbeResult?, b: ProbeResult?): Boolean {
        val mine = a?.elapsedMs ?: return false
        val theirs = b?.elapsedMs ?: return false
        if (mine <= 0 || theirs <= 0) return false
        return mine * 5 <= theirs * 4
    }

    private fun comparator(probes: Map<String, ProbeResult?>): Comparator<SourceCapability> =
        Comparator { a, b ->
            val pa = probes[a.url]
            val pb = probes[b.url]
            val ra = rank(pa)
            val rb = rank(pb)
            if (ra != rb) return@Comparator ra - rb

            val byCount = b.capabilityCount.compareTo(a.capabilityCount)
            if (byCount != 0) return@Comparator byCount

            if (pa != null && pb != null &&
                pa.passed && pb.passed && pa.elapsedMs > 0 && pb.elapsedMs > 0
            ) {
                val byTime = pa.elapsedMs.compareTo(pb.elapsedMs)
                if (byTime != 0) return@Comparator byTime
            }

            if (a.hasRespondTime != b.hasRespondTime) {
                return@Comparator if (a.hasRespondTime) -1 else 1
            }
            if (a.hasRespondTime && b.hasRespondTime && a.respondTime != b.respondTime) {
                return@Comparator a.respondTime.compareTo(b.respondTime)
            }

            val byName = a.name.compareTo(b.name)
            if (byName != 0) byName else a.url.compareTo(b.url)
        }

    /** 实测通过的最先，没测过的居中，失败的最后 */
    private fun rank(probe: ProbeResult?): Int = when {
        probe == null -> 1
        probe.passed -> 0
        else -> 2
    }
}
