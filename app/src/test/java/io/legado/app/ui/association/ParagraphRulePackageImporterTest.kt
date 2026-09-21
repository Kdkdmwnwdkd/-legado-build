package io.legado.app.ui.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphRulePackageImporterTest {

    @Test
    fun numbersConflictingNamesFromTwo() {
        assertEquals("Rule (2)", numberedImportedName("Rule", setOf("Rule")))
        assertEquals(
            "Rule (4)",
            numberedImportedName("Rule", setOf("Rule", "Rule (2)", "Rule (3)"))
        )
    }

    @Test
    fun numberedNameStaysWithinParserLimit() {
        val result = numberedImportedName("R".repeat(MAX_PARAGRAPH_RULE_NAME_CHARS), emptySet())

        assertEquals(MAX_PARAGRAPH_RULE_NAME_CHARS, result.length)
        assertTrue(result.endsWith(" (2)"))
    }

    @Test
    fun numberingRemainsCaseSensitive() {
        assertEquals("Rule (2)", numberedImportedName("Rule", setOf("Rule", "rule (2)")))
    }

    @Test
    fun conflictsIncludeExistingNamesAndDuplicatesInsidePackage() {
        assertEquals(
            3,
            countParagraphRuleNameConflicts(
                importedNames = listOf("Existing", "New", "New", "Existing"),
                existingNames = setOf("Existing")
            )
        )
    }
}
