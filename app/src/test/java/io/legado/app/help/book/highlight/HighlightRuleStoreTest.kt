package io.legado.app.help.book.highlight

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HighlightRuleStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = HighlightRuleStore(temporary.newFolder())

    @Test fun metadataSurvivesReopeningWithoutEmbeddingImageBytes() {
        val store = store()
        val image = ByteArray(500_000) { 7 }
        val id = HighlightPackageParser.sha256(image)
        val rule = HighlightRule(keyword = "text", asset = id)
        assertEquals(1, store.importPackage(HighlightPackage(listOf(rule), mapOf(id to image))).added)
        assertTrue(File(store.directory, "rules.json").length() < 2048)
        assertArrayEquals(image, store.assetFile(id)!!.readBytes())
        assertEquals(rule.id, HighlightRuleStore(store.directory).all().single().id)
    }

    @Test fun reimportDoesNotResetUserEnabledStateOrDuplicateRules() {
        val store = store()
        val rule = HighlightRule(name = "规则", keyword = "word")
        val pack = HighlightPackage(listOf(rule), emptyMap())
        store.importPackage(pack)
        store.put(store.all().single().copy(enabled = false))
        val result = store.importPackage(pack.copy(rules = listOf(rule.copy(id = "different", sortOrder = 50))))
        assertEquals(0, result.added)
        assertEquals(1, result.duplicates)
        assertFalse(store.all().single().enabled)
    }

    @Test fun equalNamesWithDifferentExpressionsRemainSeparate() {
        val store = store()
        val a = HighlightRule(name = "同名", keyword = "a")
        val b = HighlightRule(name = "同名", keyword = "b")
        assertEquals(2, store.importPackage(HighlightPackage(listOf(a, b), emptyMap())).added)
    }

    @Test fun bookAndStyledScopesAreAppliedIndependently() {
        val store = store()
        val all = HighlightRule(keyword = "all")
        val local = HighlightRule(keyword = "local", global = false, bookUrl = "A")
        val plain = HighlightRule(keyword = "plain", applyToStyledBooks = false)
        val disabled = HighlightRule(keyword = "disabled", enabled = false)
        store.importPackage(HighlightPackage(listOf(all, local, plain, disabled), emptyMap()))
        assertEquals(listOf("all", "local", "plain"), store.active("A", false).map { it.keyword })
        assertEquals(listOf("all"), store.active("B", true).map { it.keyword })
    }

    @Test fun reorderAndDeletePersistWithoutChangingEnabledFlags() {
        val store = store()
        val a = HighlightRule(keyword = "a", enabled = false)
        val b = HighlightRule(keyword = "b")
        store.importPackage(HighlightPackage(listOf(a, b), emptyMap()))
        val revision = store.revision()
        store.reorder(listOf(b.id, a.id))
        assertNotEquals(revision, store.revision())
        assertEquals(listOf("b", "a"), HighlightRuleStore(store.directory).all().map { it.keyword })
        assertFalse(store.all().last().enabled)
        store.delete(b.id)
        assertEquals(listOf(a.id), store.all().map { it.id })
    }

    @Test fun backupAndRestoreIncludeSharedAssetsAndBookBindings() {
        val original = store()
        val bytes = byteArrayOf(1, 2, 3)
        val id = HighlightPackageParser.sha256(bytes)
        val rule = HighlightRule(keyword = "bound", global = false, bookUrl = "A", asset = id)
        original.importPackage(HighlightPackage(listOf(rule), mapOf(id to bytes)))
        val backup = temporary.newFolder()
        original.backupTo(backup)
        val target = store()
        target.restoreFrom(backup)
        assertEquals("A", target.all().single().bookUrl)
        assertArrayEquals(bytes, target.assetFile(id)!!.readBytes())
    }

    @Test fun interruptedIndexReplacementCanRecoverFromTheBackup() {
        val store = store()
        store.put(HighlightRule(keyword = "saved"))
        val file = File(store.directory, "rules.json")
        assertTrue(file.renameTo(File(store.directory, "rules.json.bak")))
        val restored = HighlightRuleStore(store.directory)
        assertEquals("saved", restored.all().single().keyword)
        restored.put(restored.all().single().copy(name = "updated"))
        assertEquals("updated", HighlightRuleStore(store.directory).all().single().name)
    }

    @Test fun assetLookupRejectsPathsAndInvalidIds() {
        val store = store()
        assertNull(store.assetFile("../rules.json"))
        assertNull(store.assetFile("C:\\secret"))
        assertNull(store.assetFile("a".repeat(63)))
    }

    @Test fun invalidMutationLeavesPreviouslySavedRulesIntact() {
        val store = store()
        store.put(HighlightRule(keyword = "saved"))
        assertThrows(IllegalArgumentException::class.java) { store.put(HighlightRule(keyword = "x".repeat(16385))) }
        assertEquals("saved", HighlightRuleStore(store.directory).all().single().keyword)
    }

    @Test fun filteredReorderLeavesHiddenRulesInTheirOriginalSlots() {
        val store = store()
        val rules = (0..4).map { HighlightRule(keyword = it.toString(), enabled = it != 2) }
        store.importPackage(HighlightPackage(rules, emptyMap()))
        store.reorder(listOf(rules[4].id, rules[2].id, rules[0].id, rules[0].id, "deleted"))
        val restored = HighlightRuleStore(store.directory).all()
        assertEquals(listOf("4", "1", "2", "3", "0"), restored.map { it.keyword })
        assertEquals((0..4).toList(), restored.map { it.sortOrder })
        assertFalse(restored.single { it.id == rules[2].id }.enabled)
    }

    @Test fun savingAnOldEditorDraftDoesNotUndoASeparateReorder() {
        val store = store()
        val rules = (0..2).map { HighlightRule(keyword = it.toString()) }
        store.importPackage(HighlightPackage(rules, emptyMap()))
        val draft = store.all().first().copy(name = "编辑后的名称")
        store.reorder(rules.reversed().map { it.id })
        store.put(draft)
        val restored = HighlightRuleStore(store.directory).all()
        assertEquals(listOf("2", "1", "0"), restored.map { it.keyword })
        assertEquals("编辑后的名称", restored.last().name)
    }

    @Test fun narrowUpdatesKeepNewerFieldsAndCannotResurrectDeletedRules() {
        val store = store()
        val rule = HighlightRule(keyword = "word")
        store.put(rule)
        store.update(rule.id) { it.copy(enabled = false) }
        store.update(rule.id) { it.copy(global = false, bookUrl = "book") }
        val updated = HighlightRuleStore(store.directory).all().single()
        assertFalse(updated.enabled)
        assertEquals("book", updated.bookUrl)
        store.delete(rule.id)
        assertThrows(IllegalStateException::class.java) {
            store.update(rule.id) { it.copy(enabled = true) }
        }
        assertTrue(store.all().isEmpty())
    }

    @Test fun unchangedAndOutdatedDragOrdersDoNotInvalidateTheReader() {
        val store = store()
        val rules = (0..2).map { HighlightRule(keyword = it.toString()) }
        store.importPackage(HighlightPackage(rules, emptyMap()))
        val revision = store.revision()
        store.reorder(rules.map { it.id })
        store.reorder(listOf("deleted", rules.first().id))
        store.reorder(emptyList())
        assertEquals(revision, store.revision())
    }

    @Test fun reimportRepairsMissingOrDamagedSharedArtworkWithoutResettingRules() {
        val store = store()
        val image = byteArrayOf(1, 2, 3, 4)
        val id = HighlightPackageParser.sha256(image)
        val rule = HighlightRule(keyword = "气泡", asset = id)
        val pack = HighlightPackage(listOf(rule), mapOf(id to image))
        store.importPackage(pack)
        store.update(rule.id) { it.copy(enabled = false) }
        val original = store.all().single()
        for (missing in listOf(true, false)) {
            val file = store.assetFile(id)!!
            if (missing) assertTrue(file.delete()) else file.writeBytes(byteArrayOf(5, 6))
            val revision = store.revision()
            val report = store.importPackage(pack)
            assertEquals(0, report.added)
            assertEquals(1, report.duplicates)
            assertEquals(original, store.all().single())
            assertArrayEquals(image, store.assetFile(id)?.readBytes())
            assertNotEquals(revision, store.revision())
        }
    }
}
