package io.legado.app.help.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookTagManagementTest {

    @Test
    fun mergeTagsKeepsConfiguredOrderAndAddsExistingTags() {
        assertEquals(
            listOf("科幻", "完结", "收藏"),
            BookTagManagement.mergeTags(
                configured = listOf("科幻", "完结"),
                existing = listOf("收藏", "科幻")
            )
        )
    }

    @Test
    fun mergeTagsDeduplicatesIgnoringCaseAndWhitespace() {
        assertEquals(
            listOf("SciFi", "History"),
            BookTagManagement.mergeTags(
                configured = listOf(" SciFi "),
                existing = listOf("scifi", "History", "")
            )
        )
    }

    @Test
    fun reusableTagsExcludesCurrentTagsIgnoringCase() {
        assertEquals(
            listOf("History", "Fantasy"),
            BookTagManagement.reusableTags(
                current = listOf(" SciFi ", "Finished"),
                all = listOf("scifi", "History", "FINISHED", "Fantasy", "history")
            )
        )
    }

    @Test
    fun updateTagOnlyChangesWhenSelectionDiffers() {
        assertNull(BookTagManagement.updateTag("科幻 完结", "科幻", selected = true))
        assertEquals("科幻,完结,收藏", BookTagManagement.updateTag("科幻 完结", "收藏", true))
        assertEquals("完结", BookTagManagement.updateTag("科幻 完结", "科幻", false))
    }
}
