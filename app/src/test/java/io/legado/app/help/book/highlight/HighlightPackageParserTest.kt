package io.legado.app.help.book.highlight

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.GZIPOutputStream

class HighlightPackageParserTest {
    private fun red(json: String): ByteArray = ByteArrayOutputStream().also { output ->
        output.write(byteArrayOf(82, 69, 68, 1))
        GZIPOutputStream(output).use { it.write(json.toByteArray()) }
    }.toByteArray()
    private val basic = """{"name":"「」上色","keyword":"「([^」]*)」","isRegex":true,"enabled":true,"global":true,"styleType":"textColor","styleColorType":"teal"}"""
    private fun pack(rule: String = basic) = """{"version":1,"type":"highlightRule","data":[""" + rule + "]}"
    private fun container(metadata: String, payload: ByteArray = ByteArray(17)): ByteArray {
        val header = metadata.toByteArray()
        return byteArrayOf(82, 69, 68, 16) + ByteBuffer.allocate(4).putInt(header.size).array() + header + payload
    }
    private val encryptedHeader = """{"version":2,"resourceType":"highlightRule","payloadType":"directory","containerMode":"reedenPrivate","algorithm":"aes-256-gcm-manifest","assetMode":"plain-sequential","manifestLength":17,"manifestNonce":"7jRr9YTOA/nYqcad","createdBy":"reeden"}"""

    @Test fun readsLegacyGzipEnvelope() {
        val result = HighlightPackageParser.parse(red(pack()))
        assertEquals("「」上色", result.rules.single().name)
        assertEquals("teal", result.rules.single().styleColorType)
        assertTrue(result.rules.single().isRegex)
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun readsJsonEnvelopeArrayAndSingleObject() {
        for (input in listOf(pack(), "[" + basic + "]", basic, "\uFEFF" + pack())) {
            assertEquals("「」上色", HighlightPackageParser.parse(input.toByteArray()).rules.single().name)
        }
    }

    @Test fun routesHighlightWithoutHijackingThemesOrReplacementRules() {
        assertEquals("highlightRule", HighlightPackageParser.resourceType(red(pack())))
        assertEquals("theme", HighlightPackageParser.resourceType(red("""{"type":"theme","data":[]}""")))
        assertEquals("purifyRule", HighlightPackageParser.resourceType(red("""{"type":"purifyRule","data":[]}""")))
        assertNull(HighlightPackageParser.resourceType(byteArrayOf(0, 1, 2)))
    }

    @Test fun rejectsReplacementPackage() {
        assertThrows(IllegalStateException::class.java) {
            HighlightPackageParser.parse(red("""{"type":"purifyRule","data":[]}"""))
        }
    }

    @Test fun reportsEncryptionDeclaredByTheFileWithoutAssumingAPassword() {
        val bytes = container(encryptedHeader)
        assertEquals("highlightRule", HighlightPackageParser.resourceType(bytes))
        val error = assertThrows(HighlightPackageParser.EncryptedPackageException::class.java) {
            HighlightPackageParser.parse(bytes)
        }
        assertTrue(error.message.orEmpty().contains("AES-256-GCM"))
        assertTrue(error.message.orEmpty().contains("不代表你或规则作者设置了密码"))
    }

    @Test fun newerContainerWithoutAlgorithmIsNotAssumedToBeEncrypted() {
        val bytes = container("""{"resourceType":"highlightRule","containerMode":"reedenPrivate"}""")
        assertEquals("highlightRule", HighlightPackageParser.resourceType(bytes))
        val error = assertThrows(IllegalStateException::class.java) { HighlightPackageParser.parse(bytes) }
        assertFalse(error.message.orEmpty().contains("加密"))
    }

    @Test fun truncatedContainerHeaderCannotMasqueradeAsReadablePackage() {
        val bytes = byteArrayOf(82, 69, 68, 16, 127, 127, 127, 127)
        assertNull(HighlightPackageParser.resourceType(bytes))
        val error = assertThrows(IllegalArgumentException::class.java) { HighlightPackageParser.parse(bytes) }
        assertFalse(error is HighlightPackageParser.EncryptedPackageException)
        assertTrue(error.message.orEmpty().contains("文件头"))
    }

    @Test fun truncatedEncryptedPayloadReportsDamageBeforeCompatibility() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            HighlightPackageParser.parse(container(encryptedHeader, ByteArray(2)))
        }
        assertFalse(error is HighlightPackageParser.EncryptedPackageException)
        assertTrue(error.message.orEmpty().contains("内容不完整"))
    }

    @Test fun invalidNonceReportsInvalidParametersBeforeCompatibility() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            HighlightPackageParser.parse(container(encryptedHeader.replace("7jRr9YTOA/nYqcad", "not-base64")))
        }
        assertFalse(error is HighlightPackageParser.EncryptedPackageException)
        assertTrue(error.message.orEmpty().contains("参数"))
    }

    @Test fun encryptedThemeIsNotTreatedAsAHighlightFile() {
        val bytes = container(encryptedHeader.replace("highlightRule", "theme"))
        assertEquals("theme", HighlightPackageParser.resourceType(bytes))
        val error = assertThrows(IllegalArgumentException::class.java) { HighlightPackageParser.parse(bytes) }
        assertFalse(error is HighlightPackageParser.EncryptedPackageException)
        assertTrue(error.message.orEmpty().contains("不是高亮规则"))
    }

    @Test fun rejectsCorruptCompressionAndUnsupportedVersions() {
        assertThrows(Exception::class.java) { HighlightPackageParser.parse(byteArrayOf(82, 69, 68, 1, 1, 2)) }
        assertThrows(IllegalStateException::class.java) { HighlightPackageParser.parse(byteArrayOf(82, 69, 68, 9)) }
    }

    @Test fun readLimitsCountActualDecompressedBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            HighlightPackageParser.readLimited(ByteArrayInputStream(ByteArray(1025)), 1024)
        }
        assertEquals(1024, HighlightPackageParser.readLimited(ByteArrayInputStream(ByteArray(1024)), 1024).size)
    }

    @Test fun preservesScopeTitleAndDisabledFlags() {
        val rule = """{"name":"标题","keyword":"","enabled":false,"global":false,"bookId":"REEDEN-ID","target":"title","isMultiline":true,"applyToStyledBooks":false,"groupName":"分组","styleType":"background","sortOrder":23}"""
        val parsed = HighlightPackageParser.parse(red(pack(rule)), "legado-book").rules.single()
        assertEquals("legado-book", parsed.bookUrl)
        assertEquals("REEDEN-ID", parsed.sourceBookId)
        assertTrue(parsed.titleOnly)
        assertTrue(parsed.isMultiline)
        assertFalse(parsed.enabled)
        assertFalse(parsed.global)
        assertFalse(parsed.applyToStyledBooks)
        assertEquals(23, parsed.sortOrder)
    }

    @Test fun unboundForeignBookNeverBecomesGlobal() {
        val objectValue = JsonParser.parseString(basic).asJsonObject.apply { addProperty("global", false); addProperty("bookId", "foreign") }
        val rule = HighlightPackageParser.parse(objectValue.toString().toByteArray()).rules.single()
        assertNull(rule.bookUrl)
        assertFalse(rule.appliesTo("foreign", false))
        assertTrue(rule.importWarning.contains("绑定"))
    }

    @Test fun oneDisabledInvalidExpressionDoesNotDiscardOtherRules() {
        val invalid = """{"name":"坏表达式","keyword":"(","isRegex":true,"enabled":false,"styleType":"textColor"}"""
        val result = HighlightPackageParser.parse(red(pack(basic + "," + invalid + ",5")))
        assertEquals(2, result.rules.size)
        assertFalse(result.rules.last().enabled)
        assertTrue(result.warnings.any { it.contains("表达式") })
        assertTrue(result.warnings.any { it.contains("第 3 条") })
    }

    @Test fun invalidImageKeepsTextRuleAndExplainsFallback() {
        val obj = JsonParser.parseString(basic).asJsonObject.apply { addProperty("backgroundImageData", "not an image") }
        val result = HighlightPackageParser.parse(obj.toString().toByteArray())
        assertEquals(1, result.rules.size)
        assertNull(result.rules.single().asset)
        assertTrue(result.warnings.any { it.contains("背景图片") })
    }

    @Test fun compressedPngRoundTripsWithoutKeepingBase64InRuleIndex() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDecAAAAASUVORK5CYII=")
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(png) } }.toByteArray()
        val obj = JsonParser.parseString(basic).asJsonObject.apply {
            addProperty("backgroundImageData", Base64.getEncoder().encodeToString(gz))
        }
        val result = HighlightPackageParser.parse(red(pack(obj.toString())))
        val rule = result.rules.single()
        assertEquals(1, rule.imageWidth)
        assertEquals(1, rule.imageHeight)
        assertArrayEquals(png, result.assets.getValue(rule.asset!!))
        val exported = HighlightPackageParser.export(result.rules) { result.assets[it] }
        val again = HighlightPackageParser.parse(exported)
        assertEquals(rule.asset, again.rules.single().asset)
        assertArrayEquals(png, again.assets.getValue(rule.asset))
    }

    @Test fun missingFontIsPreservedWithClearFallbackNotice() {
        val obj = JsonParser.parseString(basic).asJsonObject.apply {
            addProperty("styleCssText", "font-family: \"reeden-font:HASH\";")
        }
        val rule = HighlightPackageParser.parse(obj.toString().toByteArray()).rules.single()
        assertTrue(rule.styleCssText.contains("reeden-font:HASH"))
        assertTrue(rule.importWarning.contains("当前阅读字体"))
    }

    @Test fun exportRefusesMissingOrDamagedArtworkInsteadOfSilentlyDroppingIt() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDecAAAAASUVORK5CYII=")
        val rule = HighlightRule(name = "气泡", keyword = "文字", asset = HighlightPackageParser.sha256(png))
        val missing = assertThrows(IllegalArgumentException::class.java) {
            HighlightPackageParser.export(listOf(rule)) { null }
        }
        assertTrue(missing.message.orEmpty().contains("图片缺失"))
        val damaged = assertThrows(IllegalArgumentException::class.java) {
            HighlightPackageParser.export(listOf(rule)) { png.copyOf().apply { this[lastIndex] = 0 } }
        }
        assertTrue(damaged.message.orEmpty().contains("图片损坏"))
    }

    @Test fun exportEnforcesTheSameRuleCountAndUtf8BudgetAsImport() {
        val rule = HighlightRule(keyword = "文字", styleCssText = "甲".repeat(32768))
        val tooMany = assertThrows(IllegalArgumentException::class.java) {
            HighlightPackageParser.export(List(HighlightPackageParser.MAX_RULES + 1) { rule }) { null }
        }
        assertTrue(tooMany.message.orEmpty().contains("分批导出"))
        // This compresses to far less than 24 MiB, but its decoded UTF-8 exceeds
        // 48 MiB. It must fail before producing a package the importer rejects.
        val tooLarge = assertThrows(IllegalArgumentException::class.java) {
            HighlightPackageParser.export(List(600) { rule }) { null }
        }
        assertTrue(tooLarge.message.orEmpty().contains("分批导出"))
    }
}
