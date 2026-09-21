package io.legado.app.help.book.highlight

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HighlightRuleStoreValidationTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun rejectBackup(json: String) {
        val target = HighlightRuleStore(temporary.newFolder())
        val saved = HighlightRule(keyword = "原有规则")
        target.put(saved)
        val index = File(target.directory, "rules.json")
        val before = index.readBytes()
        val revision = target.revision()
        val backup = temporary.newFolder()
        File(backup, "rules.json").writeText(json)

        assertThrows(IllegalArgumentException::class.java) { target.restoreFrom(backup) }
        assertArrayEquals(before, index.readBytes())
        assertEquals(listOf(saved), target.all())
        assertEquals(listOf(saved), HighlightRuleStore(target.directory).all())
        assertEquals(revision, target.revision())
    }

    @Test fun explicitNullFieldsCannotEnterTheLiveCache() {
        for (field in listOf("id", "name", "keyword", "groupName", "styleType", "styleColorType",
            "styleMode", "styleCssText", "importWarning")) {
            rejectBackup("""{"version":1,"rules":[{"$field":null}]}""")
        }
    }

    @Test fun duplicateIdsCannotCorruptOrderingOrApplyEditsToAnotherRule() {
        rejectBackup("""{"version":1,"rules":[
            {"id":"same","keyword":"甲"},
            {"id":"same","keyword":"乙"},
            {"id":"another","keyword":"丙"}
        ]}""")
    }

    @Test fun invalidAssetsAndOversizedExpressionsAreRejectedBeforeReplacingRules() {
        rejectBackup("""{"rules":[{"asset":"../outside"}]}""")
        rejectBackup("""{"rules":[{"keyword":"${"x".repeat(16385)}"}]}""")
        rejectBackup("""{"rules":[{"styleCssText":"${"x".repeat(32769)}"}]}""")
        rejectBackup("""{"rules":[{"id":""}]}""")
    }

    @Test fun nullCollectionsAndEntriesAreReportedAsInvalidBackups() {
        rejectBackup("""{"rules":null}""")
        rejectBackup("""{"rules":[null]}""")
    }

    @Test fun missingOptionalFieldsRemainCompatibleWithOldIndexes() {
        val backup = temporary.newFolder()
        File(backup, "rules.json").writeText("""{"version":1,"rules":[{"keyword":"旧规则"}]}""")
        val target = HighlightRuleStore(temporary.newFolder())
        target.restoreFrom(backup)
        val restored = target.all().single()
        assertEquals("旧规则", restored.displayName())
        assertTrue(restored.id.isNotBlank())
        assertEquals("", restored.styleCssText)
        assertEquals(restored, HighlightRuleStore(target.directory).all().single())
    }

    @Test fun invalidUpdatesAndPackagesDoNotOverwriteTheCurrentIndex() {
        val target = HighlightRuleStore(temporary.newFolder())
        val saved = HighlightRule(keyword = "saved")
        target.put(saved)
        assertThrows(IllegalArgumentException::class.java) { target.put(saved.copy(asset = "invalid")) }
        assertThrows(IllegalArgumentException::class.java) {
            target.importPackage(HighlightPackage(listOf(saved.copy(keyword = "x".repeat(16385))), emptyMap()))
        }
        assertEquals(listOf(saved), HighlightRuleStore(target.directory).all())
    }
}
