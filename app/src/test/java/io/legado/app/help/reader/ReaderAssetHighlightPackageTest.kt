package io.legado.app.help.reader

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.help.book.highlight.HighlightPackageParser
import io.legado.app.help.book.highlight.HighlightRule
import io.legado.app.help.book.highlight.HighlightRuleStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class ReaderAssetHighlightPackageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rulesRoundTripTheirSharedFontAndBackgroundThroughRed() {
        val assets = ReaderAssetStore(temporary.newFolder())
        val font = assets.import(ReaderAssetFixtures.sfnt().inputStream(), "字体")
        val image = assets.import(ReaderAssetFixtures.png().inputStream(), "背景")
        val rules = HighlightRuleStore(temporary.newFolder(), assets)
        val rule = HighlightRule(keyword = "正文", styleCssText = ReaderAssetReferences.withFont("color:red", font.id),
            asset = image.id, imageWidth = 1, imageHeight = 1)
        rules.put(rule)
        val bytes = rules.export()
        assertArrayEquals(byteArrayOf(82, 69, 68, 1), bytes.copyOf(4))
        val parsed = HighlightPackageParser.parse(bytes)
        assertEquals(font.id, parsed.resources.single().id)
        assertEquals(image.id, parsed.rules.single().asset)
        val targetAssets = ReaderAssetStore(temporary.newFolder())
        val targetRules = HighlightRuleStore(temporary.newFolder(), targetAssets)
        targetRules.importPackage(parsed)
        assertArrayEquals(ReaderAssetFixtures.sfnt(), targetAssets.verifiedBytes(font.id))
        assertArrayEquals(ReaderAssetFixtures.png(), targetRules.assetFile(image.id)!!.readBytes())
        assertEquals(0, targetRules.importPackage(parsed).added)
        assertEquals(1, targetAssets.library().assets.size)
    }

    @Test fun exportFailsExplicitlyIfReferencedFontIsMissing() {
        val rule = HighlightRule(keyword = "字", styleCssText = ReaderAssetReferences.withFont("", "a".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { HighlightPackageParser.export(listOf(rule)) { null } }
    }

    @Test fun wrongHashDuplicateIdsAndUnsupportedResourcesAreRejected() {
        val bytes = ReaderAssetFixtures.sfnt()
        val obj = JsonObject().apply {
            addProperty("id", ReaderAssetStore.sha256(bytes)); addProperty("name", "字体")
            addProperty("data", Base64.getEncoder().encodeToString(bytes))
        }
        val array = JsonArray().apply { add(obj) }
        assertEquals(1, ReaderAssetJsonCodec.read(array).size)
        array.add(obj.deepCopy())
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetJsonCodec.read(array) }
        array.remove(1); obj.addProperty("id", "a".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetJsonCodec.read(array) }
        val invalid = ByteArray(24)
        obj.addProperty("id", ReaderAssetStore.sha256(invalid)); obj.addProperty("data", Base64.getEncoder().encodeToString(invalid))
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetJsonCodec.read(array) }
    }

    @Test fun fontReplacementDoesNotChangeRuleIdentityOnReimport() {
        val assets = ReaderAssetStore(temporary.newFolder())
        val font = assets.import(ReaderAssetFixtures.sfnt().inputStream(), "字体")
        val rules = HighlightRuleStore(temporary.newFolder(), assets)
        rules.put(HighlightRule(keyword = "字", styleCssText = ReaderAssetReferences.withFont("color:red", font.id)))
        val pack = HighlightPackageParser.parse(rules.export())
        assertEquals(1, rules.importPackage(pack).duplicates)
        assertEquals(1, rules.all().size)
    }

    @Test fun jpegResourceDimensionsSurviveRedExportAndImport() {
        val assets = ReaderAssetStore(temporary.newFolder())
        val image = assets.import(ReaderAssetFixtures.jpeg().inputStream(), "背景.jpg")
        assertEquals(2, image.width); assertEquals(3, image.height)
        val rules = HighlightRuleStore(temporary.newFolder(), assets)
        rules.put(HighlightRule(keyword = "字", asset = image.id, imageWidth = image.width, imageHeight = image.height))
        val imported = HighlightPackageParser.parse(rules.export()).rules.single()
        assertEquals(2, imported.imageWidth); assertEquals(3, imported.imageHeight)
    }

    @Test fun jpegWithTrailingMetadataSurvivesRuleExportAndImport() {
        val assets = ReaderAssetStore(temporary.newFolder())
        val bytes = ReaderAssetFixtures.jpeg() + ByteArray(32)
        val image = assets.import(bytes.inputStream(), "背景.jpg")
        val source = HighlightRuleStore(temporary.newFolder(), assets)
        source.put(HighlightRule(keyword = "正文", asset = image.id, imageWidth = image.width, imageHeight = image.height))
        val target = HighlightRuleStore(temporary.newFolder(), ReaderAssetStore(temporary.newFolder()))
        target.importPackage(HighlightPackageParser.parse(source.export()))
        val rule = target.all().single()
        assertEquals(image.id, rule.asset)
        assertEquals(2, rule.imageWidth); assertEquals(3, rule.imageHeight)
        assertArrayEquals(bytes, target.assetFile(image.id)!!.readBytes())
    }
}
