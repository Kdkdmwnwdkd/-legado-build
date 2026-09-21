package io.legado.app.model.localBook.epubcore.template

import io.legado.app.help.reader.ReaderAssetFixtures
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssetStore
import io.legado.app.help.reader.ReaderTemplateAssetStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class EpubReaderTemplateAssetsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exportedTemplateIncludesDeduplicatedImagesAndFontsAndPreservesAuthorCode() {
        val assets = ReaderAssetStore(temporary.newFolder())
        val image = assets.import(ReaderAssetFixtures.png().inputStream(), "背景")
        val font = assets.import(ReaderAssetFixtures.sfnt().inputStream(), "字体")
        val template = exampleTemplate().copy(
            css = ReaderTemplateAssetStyle.font(ReaderTemplateAssetStyle.background("/* author */", image.id), font.id),
            javascript = "const image = '${ReaderAssetReferences.url(image.id)}';")
        val output = ByteArrayOutputStream()
        EpubReaderTemplatePackageArchive.writeTemplate(template, output, assets)
        val names = arrayListOf<String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names.add(entry.name); zip.closeEntry() }
        }
        assertEquals(4, names.size)
        val restoredAssets = ReaderAssetStore(temporary.newFolder())
        val staging = temporary.newFolder()
        val restored = EpubReaderTemplatePackageArchive.read(output.toByteArray().inputStream(), staging, restoredAssets)
        assertEquals(template, restored.templates.single())
        assertEquals(setOf(image.id, font.id), restoredAssets.library().assets.map { it.id }.toSet())
        assertArrayEquals(ReaderAssetFixtures.png(), restoredAssets.verifiedBytes(image.id))
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun missingAssetsFailBeforeWritingExport() {
        val template = exampleTemplate().copy(css = ReaderTemplateAssetStyle.font("", "a".repeat(64)))
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { EpubReaderTemplatePackageArchive.writeTemplate(template, output, ReaderAssetStore(temporary.newFolder())) }
        assertEquals(0, output.size())
    }

    @Test fun templateExportKeepsJpegTrailerAndReferenceStable() {
        val assets = ReaderAssetStore(temporary.newFolder())
        val bytes = ReaderAssetFixtures.jpeg() + "\nPhoto editor metadata\n".toByteArray()
        val image = assets.import(bytes.inputStream(), "背景.jpg")
        val template = exampleTemplate().copy(css = ReaderTemplateAssetStyle.background("", image.id))
        val output = ByteArrayOutputStream()
        EpubReaderTemplatePackageArchive.writeTemplate(template, output, assets)
        val restoredAssets = ReaderAssetStore(temporary.newFolder())
        val restored = EpubReaderTemplatePackageArchive.read(output.toByteArray().inputStream(), temporary.newFolder(), restoredAssets)
        assertEquals(template, restored.templates.single())
        assertEquals(image, restoredAssets.find(image.id))
        assertArrayEquals(bytes, restoredAssets.verifiedBytes(image.id))
    }

    @Test fun rawJsonCanReferenceExistingLocalAssetsButCannotSilentlyLoseThem() {
        val assets = ReaderAssetStore(temporary.newFolder())
        val image = assets.import(ReaderAssetFixtures.png().inputStream(), "背景")
        val template = exampleTemplate().copy(css = ReaderTemplateAssetStyle.background("", image.id))
        assertEquals(template, EpubReaderTemplatePackageArchive.read(template.toJson().byteInputStream(), temporary.newFolder(), assets).templates.single())
        assertThrows(IllegalArgumentException::class.java) {
            EpubReaderTemplatePackageArchive.read(template.toJson().byteInputStream(), temporary.newFolder(), ReaderAssetStore(temporary.newFolder()))
        }
    }

    @Test fun corruptedBundledResourceDoesNotPartiallyModifyDestination() {
        val assets = ReaderAssetStore(temporary.newFolder())
        val image = assets.import(ReaderAssetFixtures.png().inputStream(), "背景")
        val template = exampleTemplate().copy(css = ReaderTemplateAssetStyle.background("", image.id))
        val output = ByteArrayOutputStream()
        EpubReaderTemplatePackageArchive.writeTemplate(template, output, assets)
        val bytes = output.toByteArray()
        val signature = ReaderAssetFixtures.png()
        val offset = bytes.indices.first { index -> index + signature.size <= bytes.size &&
            bytes.copyOfRange(index, index + 8).contentEquals(signature.copyOf(8)) }
        bytes[offset + 20] = 99
        val destination = ReaderAssetStore(temporary.newFolder())
        assertThrows(java.io.IOException::class.java) { EpubReaderTemplatePackageArchive.read(bytes.inputStream(), temporary.newFolder(), destination) }
        assertTrue(destination.library().assets.isEmpty())
    }
}
