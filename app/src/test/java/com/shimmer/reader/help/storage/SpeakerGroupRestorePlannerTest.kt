package com.shimmer.reader.help.storage

import com.shimmer.reader.data.entities.ReadAloudSpeakerGroup
import com.shimmer.reader.data.entities.ReadAloudSpeakerGroupItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerGroupRestorePlannerTest {

    @Test
    fun sameNameGroupReusesLocalIdAndSkipsDuplicateItems() {
        val localGroup = ReadAloudSpeakerGroup(id = 7L, name = "睡前", sortOrder = 0)
        val localItems = listOf(
            item(groupId = 7L, speaker = "云希", sortOrder = 0)
        )
        val plan = SpeakerGroupRestorePlanner.plan(
            localGroups = listOf(localGroup),
            localItems = localItems,
            incomingGroups = listOf(ReadAloudSpeakerGroup(id = 1L, name = " 睡前 ", sortOrder = 0)),
            incomingItems = listOf(
                item(groupId = 1L, speaker = "云希"),
                item(groupId = 1L, speaker = "云扬")
            )
        )

        assertEquals(7L, plan.idMap[1L])
        assertTrue(plan.groups.isEmpty())
        // 云希 在本机组里已经有了，只补 云扬，序号接在本机后面
        assertEquals(listOf("云扬"), plan.items.map { it.speakerName })
        assertEquals(7L, plan.items.single().groupId)
        assertEquals(1, plan.items.single().sortOrder)
    }

    @Test
    fun newGroupGetsPlaceholderIdAndItemsFollowIt() {
        val plan = SpeakerGroupRestorePlanner.plan(
            localGroups = listOf(ReadAloudSpeakerGroup(id = 3L, name = "本机组", sortOrder = 4)),
            localItems = emptyList(),
            incomingGroups = listOf(ReadAloudSpeakerGroup(id = 11L, name = "通勤", sortOrder = 0)),
            incomingItems = listOf(item(groupId = 11L, speaker = "云希"))
        )

        val newGroup = plan.groups.single()
        assertEquals("通勤", newGroup.name)
        assertTrue(newGroup.id < 0L)
        // 新组排在本机已有的组后面
        assertEquals(5, newGroup.sortOrder)
        assertEquals(newGroup.id, plan.idMap[11L])
        assertEquals(newGroup.id, plan.items.single().groupId)
        assertEquals(0, plan.items.single().sortOrder)
    }

    @Test
    fun itemsOfUnknownGroupAreDropped() {
        val plan = SpeakerGroupRestorePlanner.plan(
            localGroups = emptyList(),
            localItems = emptyList(),
            incomingGroups = emptyList(),
            incomingItems = listOf(item(groupId = 99L, speaker = "云希"))
        )

        assertTrue(plan.groups.isEmpty())
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun invalidGroupIsSkippedWithItsItems() {
        val plan = SpeakerGroupRestorePlanner.plan(
            localGroups = emptyList(),
            localItems = emptyList(),
            incomingGroups = listOf(ReadAloudSpeakerGroup(id = 1L, name = "失效发言人")),
            incomingItems = listOf(item(groupId = 1L, speaker = "云希")),
            isInvalidGroupName = { it == "失效发言人" }
        )

        assertTrue(plan.groups.isEmpty())
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun sameStyleDifferentEngineIsKept() {
        val plan = SpeakerGroupRestorePlanner.plan(
            localGroups = listOf(ReadAloudSpeakerGroup(id = 2L, name = "睡前")),
            localItems = listOf(item(groupId = 2L, speaker = "云希", engineType = "http")),
            incomingGroups = listOf(ReadAloudSpeakerGroup(id = 1L, name = "睡前")),
            incomingItems = listOf(
                item(groupId = 1L, speaker = "云希", engineType = "system")
            )
        )

        assertEquals(1, plan.items.size)
        assertEquals("system", plan.items.single().engineType)
    }

    private fun item(
        groupId: Long,
        speaker: String,
        engineType: String = "system",
        sortOrder: Int = 0
    ) = ReadAloudSpeakerGroupItem(
        groupId = groupId,
        engineType = engineType,
        engineValue = "engine",
        speakerName = speaker,
        toneID = speaker,
        sortOrder = sortOrder
    )
}
