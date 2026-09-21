package io.legado.app.ui.association

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportBookSourceViewModelTest {

    @Test
    fun staleLocalCandidateFromOldUrlIsIgnored() {
        val imported = BookSource(
            bookSourceUrl = "https://new.example/source",
            bookSourceName = "remote name",
            bookSourceGroup = "remote group",
            customOrder = 7,
            enabled = true,
            enabledExplore = true
        )
        val oldLocal = BookSourcePart(
            bookSourceUrl = "https://old.example/source",
            bookSourceName = "old local name",
            bookSourceGroup = "old local group",
            customOrder = 99,
            enabled = false,
            enabledExplore = false
        )

        val prepared = prepareBookSourceForImport(
            importedSource = imported,
            localCandidate = oldLocal,
            options = keepAllLocalOptions()
        )

        assertEquals("remote name", prepared.bookSourceName)
        assertEquals("remote group", prepared.bookSourceGroup)
        assertEquals(7, prepared.customOrder)
        assertTrue(prepared.enabled)
        assertTrue(prepared.enabledExplore)
        assertNull(resolveBookSourceImportConflict(imported, oldLocal).localSource)
    }

    @Test
    fun matchingLocalCandidateSuppliesPreservedFields() {
        val url = "https://same.example/source"
        val imported = BookSource(
            bookSourceUrl = url,
            bookSourceName = "remote name",
            bookSourceGroup = "remote group",
            customOrder = 7,
            enabled = true,
            enabledExplore = true
        )
        val matchingLocal = BookSourcePart(
            bookSourceUrl = url,
            bookSourceName = "local name",
            bookSourceGroup = "local group",
            customOrder = 99,
            enabled = false,
            enabledExplore = false
        )

        val prepared = prepareBookSourceForImport(
            importedSource = imported,
            localCandidate = matchingLocal,
            options = keepAllLocalOptions()
        )

        assertEquals("local name", prepared.bookSourceName)
        assertEquals("local group", prepared.bookSourceGroup)
        assertEquals(99, prepared.customOrder)
        assertEquals(false, prepared.enabled)
        assertEquals(false, prepared.enabledExplore)
    }

    @Test
    fun conflictSelectionKeepsExistingTimestampPolicy() {
        val imported = BookSource(
            bookSourceUrl = "https://same.example/source",
            lastUpdateTime = 20
        )
        val olderLocal = BookSourcePart(
            bookSourceUrl = imported.bookSourceUrl,
            lastUpdateTime = 10
        )

        val conflict = resolveBookSourceImportConflict(imported, olderLocal)

        assertEquals(false, conflict.isNew)
        assertTrue(conflict.isUpdate)
        assertTrue(conflict.selectedByDefault)
    }

    private fun keepAllLocalOptions() = BookSourceImportOptions(
        keepName = true,
        keepGroup = true,
        keepEnable = true,
        groupName = null,
        addGroup = false
    )
}
