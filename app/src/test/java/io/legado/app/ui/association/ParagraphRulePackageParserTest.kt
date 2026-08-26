package io.legado.app.ui.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphRulePackageParserTest {

    @Test
    fun parsesLegacySingleRule() {
        val parsed = ParagraphRulePackageParser.parse(ruleJson("Single"))

        assertEquals(1, parsed.entries.size)
        assertEquals("Single", parsed.entries.single().rule.name)
        assertTrue(parsed.entries.single().vars.isEmpty())
        assertFalse(parsed.entries.single().varsIncluded)
    }

    @Test
    fun parsesLegacyRuleArray() {
        val parsed = ParagraphRulePackageParser.parse(
            "[${ruleJson("First")},${ruleJson("Second")}]"
        )

        assertEquals(listOf("First", "Second"), parsed.entries.map { it.rule.name })
    }

    @Test
    fun parsesVersionedPackageWithVars() {
        val parsed = ParagraphRulePackageParser.parse(
            """
            {
              "format": "legado.paragraph-rules",
              "schemaVersion": 1,
              "rules": [{
                "exportId": "stable-one",
                "rule": ${ruleJson("Packaged")},
                "vars": {"token": "value"}
              }]
            }
            """.trimIndent()
        )

        val entry = parsed.entries.single()
        assertEquals("stable-one", entry.exportId)
        assertEquals(mapOf("token" to "value"), entry.vars)
        assertTrue(entry.varsIncluded)
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsUnsupportedSchema() {
        ParagraphRulePackageParser.parse(
            """{"format":"legado.paragraph-rules","schemaVersion":2,"rules":[]}"""
        )
    }

    @Test
    fun keepsDuplicateNamesForConflictResolution() {
        val parsed = ParagraphRulePackageParser.parse(
            "[${ruleJson("Duplicate")},${ruleJson("duplicate")}]"
        )

        assertEquals(listOf("Duplicate", "duplicate"), parsed.entries.map { it.rule.name })
    }

    @Test
    fun acceptsMultilineLoginScript() {
        val parsed = ParagraphRulePackageParser.parse(
            """{"name":"Login","script":"return content","loginUrl":"@js:\nlet token = 'a b';\nreturn token;","timeoutMillisecond":3000}"""
        )

        assertEquals("@js:\nlet token = 'a b';\nreturn token;", parsed.entries.single().rule.loginUrl)
    }

    @Test
    fun preservesNonPositiveTimeoutUsedAsDefault() {
        val parsed = ParagraphRulePackageParser.parse(
            """{"name":"Default timeout","script":"return content","timeoutMillisecond":0}"""
        )

        assertEquals(0L, parsed.entries.single().rule.timeoutMillisecond)
    }

    @Test
    fun acceptsWhitespaceVariableNamesUsedByRuntimeApi() {
        val parsed = ParagraphRulePackageParser.parse(
            """
            {
              "format": "legado.paragraph-rules",
              "schemaVersion": 1,
              "rules": [{"rule": ${ruleJson("Vars")}, "vars": {"display name": "value"}}]
            }
            """.trimIndent()
        )

        assertEquals("value", parsed.entries.single().vars["display name"])
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsNonStringVars() {
        ParagraphRulePackageParser.parse(
            """
            {
              "format": "legado.paragraph-rules",
              "schemaVersion": 1,
              "rules": [{"rule": ${ruleJson("Vars")}, "vars": {"bad": 1}}]
            }
            """.trimIndent()
        )
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsBlankScript() {
        ParagraphRulePackageParser.parse(ruleJson("Blank", script = ""))
    }

    private fun ruleJson(name: String, script: String = "return content") =
        """{"name":"$name","script":"$script","timeoutMillisecond":3000}"""
}
