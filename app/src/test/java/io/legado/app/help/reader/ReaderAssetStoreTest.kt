package io.legado.app.help.reader

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class ReaderAssetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = ReaderAssetStore(temporary.newFolder())

    @Test fun importsByContentAndKeepsReferencesStableAcrossRename() {
        val store = store()
        val bytes = ReaderAssetFixtures.png()
        val first = store.import(bytes.inputStream(), "红色.png")
        val duplicate = store.import(bytes.inputStream(), "另一个名字.png")
        assertEquals(first, duplicate); assertEquals(1, store.library().assets.size)
        store.rename(first.id, "新名称")
        assertEquals(first.id, store.library().assets.single().id)
        assertEquals("新名称", ReaderAssetStore(store.directory).find(first.id)?.name)
        assertArrayEquals(bytes, store.verifiedBytes(first.id))
        assertFalse(File(store.directory, "library.json").readText().contains("data:image"))
    }

    @Test fun jpegTrailerSurvivesImportDeduplicationAndBackup() {
        val original = store()
        val bytes = ReaderAssetFixtures.jpeg() + "\nPhoto editor metadata\n".toByteArray()
        val asset = original.import(bytes.inputStream(), "背景.jpeg")
        assertEquals("image/jpeg", asset.mimeType); assertEquals("jpg", asset.extension)
        assertEquals(2, asset.width); assertEquals(3, asset.height)
        assertEquals(ReaderAssetStore.sha256(bytes), asset.id)
        assertEquals(asset, original.import(bytes.inputStream(), "副本.jpg"))
        assertEquals(1, original.library().assets.size)
        assertArrayEquals(bytes, original.verifiedBytes(asset.id))
        val backup = temporary.newFolder()
        original.backupTo(backup)
        val restored = store(); restored.restoreFrom(backup)
        assertEquals(asset, restored.find(asset.id))
        assertArrayEquals(bytes, restored.verifiedBytes(asset.id))
    }

    @Test fun sameNameDifferentContentsRemainDifferentAssets() {
        val store = store()
        val a = store.import(ReaderAssetFixtures.png(255).inputStream(), "图.png")
        val b = store.import(ReaderAssetFixtures.png(100).inputStream(), "图.png")
        assertNotEquals(a.id, b.id); assertEquals(2, store.library().assets.size)
    }

    @Test fun extensionComesFromContentAndNamesCannotBecomePaths() {
        val store = store()
        val asset = store.import(ReaderAssetFixtures.png().inputStream(), "../wrong.ttf")
        assertEquals("image", asset.kind); assertEquals("png", asset.extension)
        assertFalse(asset.name.contains('/'))
        assertNull(store.file("../../outside"))
    }

    @Test fun malformedAndHashMismatchedImportsLeaveNoIndexOrTemporaryFiles() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.import(ByteArray(100).inputStream(), "bad.ttf") }
        assertThrows(IllegalArgumentException::class.java) { store.import(ReaderAssetFixtures.png().inputStream(), "wrong.png", "a".repeat(64)) }
        assertTrue(store.library().assets.isEmpty())
        assertTrue(File(store.directory, "files").listFiles().orEmpty().isEmpty())
    }

    @Test fun reimportRepairsCorruptFileWithoutChangingNameOrId() {
        val store = store(); val bytes = ReaderAssetFixtures.png()
        val asset = store.import(bytes.inputStream(), "原名称")
        val revision = store.revision()
        store.file(asset.id)!!.writeBytes(bytes.copyOf().apply { this[20] = 42 })
        assertThrows(IllegalArgumentException::class.java) { store.verifiedBytes(asset.id) }
        val repaired = store.import(bytes.inputStream(), "新文件名")
        assertEquals(asset, repaired); assertNotEquals(revision, store.revision())
        assertArrayEquals(bytes, store.verifiedBytes(asset.id))
    }

    @Test fun backupIncludesImportedBytesAndExcludesDeviceSpecificGrants() {
        val original = store()
        val asset = original.import(ReaderAssetFixtures.png().inputStream(), "背景")
        original.addFolder("本机文件夹", "content://local/tree/a")
        val backup = temporary.newFolder()
        original.backupTo(backup)
        assertTrue(ReaderAssetStore(backup).library().folders.isEmpty())
        val restored = store(); restored.restoreFrom(backup)
        assertEquals(asset, restored.find(asset.id))
        assertArrayEquals(original.verifiedBytes(asset.id), restored.verifiedBytes(asset.id))
    }

    @Test fun corruptBatchFailsBeforeImportingAnyMember() {
        val store = store()
        val first = ReaderAssetFixtures.png()
        val good = ReaderAssetPayload(ReaderAssetStore.sha256(first), "good", first)
        val bad = ReaderAssetPayload("a".repeat(64), "bad", ReaderAssetFixtures.sfnt())
        assertThrows(IllegalArgumentException::class.java) { store.importAll(listOf(good, bad)) }
        assertTrue(store.library().assets.isEmpty())
    }

    @Test fun restoreValidatesEveryResourceBeforeMutatingExistingLibrary() {
        val target = store(); target.import(ReaderAssetFixtures.png(10).inputStream(), "保留")
        val before = target.library()
        val source = store()
        source.import(ReaderAssetFixtures.png(20).inputStream(), "新图片")
        val broken = source.import(ReaderAssetFixtures.png(30).inputStream(), "损坏")
        source.file(broken.id)!!.writeBytes(ByteArray(broken.size.toInt()))
        assertThrows(IllegalArgumentException::class.java) { target.restoreFrom(source.directory) }
        assertEquals(before, target.library())
    }

    @Test fun missingAndSpoofedMetadataCannotBeExportedAsOtherMimeTypes() {
        val original = store()
        val asset = original.import(ReaderAssetFixtures.png().inputStream(), "图片")
        val index = File(original.directory, "library.json")
        index.writeText(Gson().toJson(ReaderAssetLibrary(assets = listOf(asset.copy(mimeType = "text/html")))))
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetStore(original.directory).library() }
        index.writeText(Gson().toJson(ReaderAssetLibrary(assets = listOf(asset.copy(width = 2)))))
        assertThrows(IllegalArgumentException::class.java) { ReaderAssetStore(original.directory).verifiedBytes(asset.id) }
    }

    @Test fun localFolderLinksAreDeduplicatedAndRemovingThemKeepsImports() {
        val store = store()
        val asset = store.import(ReaderAssetFixtures.png().inputStream(), "素材")
        val folder = store.addFolder("文件夹", "file:///storage/素材")
        assertEquals(folder, store.addFolder("重复", "file:///storage/素材"))
        assertThrows(IllegalArgumentException::class.java) { store.addFolder("网络", "https://example.com") }
        store.removeFolder(folder.id)
        assertTrue(store.library().folders.isEmpty()); assertNotNull(store.file(asset.id))
    }

    @Test fun hugeUnknownLengthInputStopsBeforeItCanFillStorage() {
        val store = store()
        val stream = object : InputStream() {
            var count = 0L
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { count += len; return len }
        }
        assertThrows(IllegalArgumentException::class.java) { store.import(stream, "too-large.ttf") }
        assertTrue(stream.count <= ReaderAssetFormat.MAX_FILE_BYTES + 8192)
        assertTrue(File(store.directory, "files").listFiles().orEmpty().isEmpty())
    }

    @Test fun deletionAlsoRemovesACorruptLengthFile() {
        val store = store(); val asset = store.import(ReaderAssetFixtures.png().inputStream(), "图片")
        val file = store.file(asset.id)!!; file.writeBytes(byteArrayOf(0))
        store.delete(asset.id)
        assertFalse(file.exists()); assertNull(store.find(asset.id))
    }
}
