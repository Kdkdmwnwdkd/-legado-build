package io.legado.app.ui.book.cache

import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioCachePackageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyManifestIsNeverTreatedAsComplete() {
        val manifest = GSON.fromJsonObject<AudioCacheManifest>(
            """{"bookName":"Book","author":"Author","bookUrl":"book","chapters":[]}"""
        ).getOrThrow()

        assertEquals(1, manifest.schemaVersion)
        assertFalse(manifest.hasCompleteCatalog)
        manifest.validateForRestore("book")
    }

    @Test
    fun incompleteRestoreKeepsExistingCatalogAndVolumes() {
        val existing = listOf(
            chapter(0, "volume", isVolume = true),
            chapter(1, "one", resourceUrl = "audio-1"),
            chapter(2, "two"),
            chapter(3, "volume-2", isVolume = true),
            chapter(4, "four")
        )
        val incoming = listOf(chapter(2, "two", resourceUrl = "audio-2"))

        val merged = mergeRestoredAudioCatalog(existing, incoming, replaceCatalog = false)

        assertEquals(5, merged.size)
        assertEquals(2, merged.count { it.isVolume })
        assertEquals("audio-2", merged.first { it.index == 2 }.resourceUrl)
    }

    @Test
    fun importedAudioResourceUrlReplacesStaleDatabaseUrl() {
        val existing = listOf(chapter(1, "one", resourceUrl = "old-audio"))
        val incoming = listOf(chapter(1, "one", resourceUrl = "restored-audio"))

        val merged = mergeRestoredAudioCatalog(
            existing = existing,
            incoming = incoming,
            replaceCatalog = false,
            preferIncomingResourceUrl = { true }
        )

        assertEquals("restored-audio", merged.single().resourceUrl)
    }

    @Test
    fun completeRestoreCanReplaceCatalog() {
        val existing = listOf(chapter(0, "old"), chapter(1, "old-2"))
        val incoming = listOf(chapter(0, "new"))

        val restored = mergeRestoredAudioCatalog(existing, incoming, replaceCatalog = true)

        assertEquals(listOf("new"), restored.map { it.title })
    }

    @Test
    fun actualFileCountIgnoresInvalidAndEmptyFiles() {
        val dir = temporaryFolder.newFolder("audio")
        dir.resolve("0_0_4_cache.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
        dir.resolve("1_10_2_other.bin").writeBytes(byteArrayOf(1, 2))
        dir.resolve("invalid.bin").writeBytes(byteArrayOf(1))
        dir.resolve("0_20_0_empty.bin").writeBytes(byteArrayOf(1))
        dir.resolve("0_30_1_zero.bin").writeBytes(byteArrayOf())

        assertEquals(2, countImportableAudioFiles(dir))
    }

    @Test
    fun manifestRejectsDuplicateOrUnsafeCacheDirectories() {
        val duplicate = AudioCacheManifest(
            bookUrl = "book",
            chapters = listOf(
                AudioCacheManifest.Chapter(index = 0, title = "one", cacheDir = "same"),
                AudioCacheManifest.Chapter(index = 1, title = "two", cacheDir = "same")
            )
        )
        val unsafe = AudioCacheManifest(
            bookUrl = "book",
            chapters = listOf(
                AudioCacheManifest.Chapter(index = 0, title = "one", cacheDir = "../outside")
            )
        )

        assertTrue(runCatching { duplicate.validateForRestore("book") }.isFailure)
        assertTrue(runCatching { unsafe.validateForRestore("book") }.isFailure)
    }

    @Test
    fun manifestAllowsMultipleVolumeHeadersWithBlankUrls() {
        val manifest = AudioCacheManifest(
            bookUrl = "book",
            chapters = listOf(
                AudioCacheManifest.Chapter(index = 0, title = "volume-1", isVolume = true, url = ""),
                AudioCacheManifest.Chapter(index = 2, title = "volume-2", isVolume = true, url = "")
            )
        )

        manifest.validateForRestore("book")
    }

    @Test
    fun manifestRejectsInvalidIndexesAndOversizedFields() {
        val invalidIndex = AudioCacheManifest(
            bookUrl = "book",
            chapters = listOf(AudioCacheManifest.Chapter(index = -1, title = "one"))
        )
        val oversizedTitle = AudioCacheManifest(
            bookUrl = "book",
            chapters = listOf(
                AudioCacheManifest.Chapter(index = 0, title = "x".repeat(20 * 1024))
            )
        )

        assertTrue(runCatching { invalidIndex.validateForRestore("book") }.isFailure)
        assertTrue(runCatching { oversizedTitle.validateForRestore("book") }.isFailure)
    }

    @Test
    fun packageMergePreservesRemoteOnlyChapterAndUsesLocalCachedChapter() {
        val remote = listOf(
            AudioCacheManifest.Chapter(
                index = 0,
                title = "remote-only",
                url = "remote-only",
                resourceUrl = "remote-audio",
                cacheDir = "remote-0",
                fileCount = 1
            ),
            AudioCacheManifest.Chapter(
                index = 1,
                title = "shared",
                url = "shared",
                resourceUrl = "old-audio",
                cacheDir = "remote-1",
                fileCount = 2
            )
        )
        val local = listOf(
            chapter(1, "shared", resourceUrl = "new-audio").copy(url = "shared"),
            chapter(2, "local-only", resourceUrl = "local-audio")
        )

        val merged = mergeAudioPackageChapters(remote, local) { it.resourceUrl != null }

        assertEquals(3, merged.size)
        assertEquals("remote-0", merged.first { it.chapter.url == "remote-only" }.chapter.cacheDir)
        val shared = merged.first { it.chapter.url == "shared" }
        assertEquals("remote-1", shared.chapter.cacheDir)
        assertEquals("new-audio", shared.chapter.resourceUrl)
        assertTrue(shared.replacePackagedFiles)
        assertTrue(merged.first { it.chapter.title == "local-only" }.chapter.cacheDir!!.startsWith("c_2_"))
    }

    private fun chapter(
        index: Int,
        title: String,
        isVolume: Boolean = false,
        resourceUrl: String? = null
    ): BookChapter {
        return BookChapter(
            url = "chapter-$index",
            title = title,
            isVolume = isVolume,
            bookUrl = "book",
            index = index,
            resourceUrl = resourceUrl
        )
    }
}
