package com.shimmer.reader.help.storage

import com.shimmer.reader.data.entities.ReadAloudSpeakerGroup
import com.shimmer.reader.data.entities.ReadAloudSpeakerGroupItem

/**
 * 朗读音色组由「组」和「组内成员」两张表组成，成员只有 groupId 一条线索挂回组上。
 *
 * 恢复备份时不能照抄 id：新机器上的 id 是自增的，撞上了会把成员塞进别人的组，
 * 没撞上就指向一个不存在的组，界面上看音色组是空的。所以先把「备份里的组 id → 目标组 id」
 * 算清楚，再按映射写成员。同名组直接并入本机已有的那个，免得同一个音色组出现两份。
 */
object SpeakerGroupRestorePlanner {

    /** 待插入的新组先拿负数占位，真正入库时由恢复流程换成自增 id */
    private const val NEW_GROUP_ID_BASE = -1L

    data class Plan(
        /** 需要新建的组，id 为负数占位 */
        val groups: List<ReadAloudSpeakerGroup>,
        /** 需要写入的成员，groupId 已经指向占位 id 或本机已有的组 */
        val items: List<ReadAloudSpeakerGroupItem>,
        /** 备份里的组 id → 目标组 id（负数表示新建） */
        val idMap: Map<Long, Long>
    )

    fun plan(
        localGroups: List<ReadAloudSpeakerGroup>,
        localItems: List<ReadAloudSpeakerGroupItem>,
        incomingGroups: List<ReadAloudSpeakerGroup>,
        incomingItems: List<ReadAloudSpeakerGroupItem>,
        isInvalidGroupName: (String) -> Boolean = { false }
    ): Plan {
        val groups = arrayListOf<ReadAloudSpeakerGroup>()
        val idMap = hashMapOf<Long, Long>()
        val localByName = localGroups
            .filter { it.name.isNotBlank() }
            .associateBy { it.name.trim() }
            .toMutableMap()
        var nextSortOrder = (localGroups.maxOfOrNull { it.sortOrder } ?: -1) + 1
        var nextPlaceholder = NEW_GROUP_ID_BASE

        incomingGroups.forEach { incoming ->
            val name = incoming.name.trim()
            if (isInvalidGroupName(name)) return@forEach
            val existing = name.takeIf { it.isNotEmpty() }?.let { localByName[it] }
            if (existing != null) {
                idMap[incoming.id] = existing.id
                return@forEach
            }
            val placeholder = nextPlaceholder--
            val copy = incoming.copy(id = placeholder, name = name, sortOrder = nextSortOrder++)
            groups += copy
            idMap[incoming.id] = placeholder
            if (name.isNotEmpty()) localByName[name] = copy
        }

        // 目标组里已有的成员：同引擎、同音色算重复，恢复时跳过，别把组里的发言人翻倍
        val seenKeys = hashMapOf<Long, MutableSet<String>>()
        val nextItemOrder = hashMapOf<Long, Int>()
        localItems.forEach { item ->
            seenKeys.getOrPut(item.groupId) { hashSetOf() }.add(itemKey(item))
            val order = item.sortOrder + 1
            if (order > (nextItemOrder[item.groupId] ?: 0)) {
                nextItemOrder[item.groupId] = order
            }
        }

        val items = arrayListOf<ReadAloudSpeakerGroupItem>()
        incomingItems.forEach { incoming ->
            val targetGroupId = idMap[incoming.groupId] ?: return@forEach
            val seen = seenKeys.getOrPut(targetGroupId) { hashSetOf() }
            if (!seen.add(itemKey(incoming))) return@forEach
            val order = nextItemOrder[targetGroupId] ?: 0
            nextItemOrder[targetGroupId] = order + 1
            items += incoming.copy(id = 0L, groupId = targetGroupId, sortOrder = order)
        }

        return Plan(groups = groups, items = items, idMap = idMap)
    }

    private fun itemKey(item: ReadAloudSpeakerGroupItem): String {
        return listOf(item.engineType, item.engineValue, item.toneID, item.speakerName)
            .joinToString("\u0000") { it.trim() }
    }
}
