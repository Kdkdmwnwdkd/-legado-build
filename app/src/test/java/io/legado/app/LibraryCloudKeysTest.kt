package io.legado.app

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.library.LibraryCloudContent
import io.legado.app.help.book.library.LibraryCloudKeys
import io.legado.app.help.book.library.LibraryCloudPaths
import io.legado.app.help.book.library.LibraryChapterManifestV3
import io.legado.app.help.book.library.LibraryChapterPayloadV3
import io.legado.app.help.book.library.LibraryChapterVariantV3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCloudKeysTest {

    @Test
    fun relaxedTitleMatchesArabicAndChineseChapterNumbers() {
        assertEquals(
            LibraryCloudKeys.relaxedTitle("第1章 宝库。"),
            LibraryCloudKeys.relaxedTitle("第一章 宝 库")
        )
    }

    @Test
    fun relaxedTitleKeepsSemanticTitleText() {
        assertEquals("714人头树", LibraryCloudKeys.relaxedTitle("第七百一十四章 人头树"))
    }

    @Test
    fun sharedBookKeyIgnoresAuthorForCrossSourceReads() {
        val first = Book(name = "超神机械师", author = "齐佩甲")
        val second = Book(name = "超神机械师", author = "")
        assertEquals(LibraryCloudKeys.sharedBookKey(first), LibraryCloudKeys.sharedBookKey(second))
    }

    @Test
    fun variantPathIsStableWhenChapterUrlChanges() {
        val sourceKey = LibraryCloudKeys.sourceKey("https://example.com/source.json")
        val chapter = BookChapter(title = "第一章 宝库", url = "/a")
        val changedUrlChapter = chapter.copy(url = "/b")
        val matchKey = LibraryCloudKeys.variantMatchKeys(chapter).first()
        val changedMatchKey = LibraryCloudKeys.variantMatchKeys(changedUrlChapter).first()
        assertEquals(matchKey, changedMatchKey)
        assertEquals(
            LibraryCloudPaths.variantChapterPath("book", matchKey, sourceKey),
            LibraryCloudPaths.variantChapterPath("book", changedMatchKey, sourceKey)
        )
    }

    @Test
    fun relaxedTitleIgnoresInvisibleAndDecorativeChars() {
        val clean = BookChapter(title = "第31章 婚姻")
        val dirty = BookChapter(title = "\uFE0F 第\u200B31章\u00A0婚姻")
        assertEquals(LibraryCloudKeys.relaxedTitle(clean.title), LibraryCloudKeys.relaxedTitle(dirty.title))
        assertEquals(LibraryCloudKeys.relaxedTitleKey(clean), LibraryCloudKeys.relaxedTitleKey(dirty))
    }

    @Test
    fun ordinalTitleMatchesArabicAndChineseNumbers() {
        assertEquals("31", LibraryCloudKeys.chapterOrdinal("第31章 婚姻"))
        assertEquals("31", LibraryCloudKeys.chapterOrdinal("第三十一章 婚姻"))
        assertEquals(
            LibraryCloudKeys.ordinalTitleKey(BookChapter(title = "第031章 婚姻")),
            LibraryCloudKeys.ordinalTitleKey(BookChapter(title = "第三十一章 婚姻"))
        )
    }

    @Test
    fun uploadTextDropsHtmlAndParagraphCommentImages() {
        val html = """
            <p>正文<img src="dp:12,{&quot;status&quot;:&quot;normal&quot;}">一</p>
            <style>.x{color:red}</style>
            <p>二<br>三</p>
        """.trimIndent()
        assertEquals("正文一\n二\n三", LibraryCloudContent.toUploadText(html))
    }

    @Test
    fun v3ChapterKeyUsesTitleNotSourceUrl() {
        val first = BookChapter(index = 104, title = "第31章 婚姻", url = "data:content")
        val second = BookChapter(index = 100, title = " 第三十一章 婚姻", url = "https://example.com/a")
        assertEquals(LibraryCloudKeys.libraryChapterKey(first), LibraryCloudKeys.libraryChapterKey(second))
    }

    @Test
    fun v3ManifestMatchesCrossSourceChapter() {
        val book = Book(name = "廓晋", author = "榴弹怕水")
        val chapter = BookChapter(title = " 第31章 婚姻")
        val manifest = LibraryChapterManifestV3(
            name = "廓晋",
            author = "榴弹怕水",
            title = "第31章 婚姻",
            normalizedTitle = LibraryCloudKeys.normalize("第31章 婚姻"),
            relaxedTitle = LibraryCloudKeys.relaxedTitle("第31章 婚姻"),
            ordinalTitle = LibraryCloudKeys.chapterOrdinal("第31章 婚姻").orEmpty(),
            variants = listOf(
                LibraryChapterVariantV3(
                    title = "第31章 婚姻",
                    relaxedTitle = LibraryCloudKeys.relaxedTitle("第31章 婚姻"),
                    ordinalTitle = "31",
                    payloadPath = "v3/books/book/chapters/chapter/sources/source.json.gz"
                )
            )
        )
        assertTrue(LibraryCloudKeys.manifestMatches(book, chapter, manifest))
        assertTrue(LibraryCloudKeys.variantMatches(chapter, manifest.variants.first()))
    }

    @Test
    fun v3PayloadPathIsSingleSourceObject() {
        val book = Book(name = "廓晋", author = "榴弹怕水")
        val chapter = BookChapter(title = "第31章 婚姻")
        val bookKey = LibraryCloudKeys.bookKey(book)
        val chapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
        val sourceKey = LibraryCloudKeys.sourceKey("https://search.qingread.icu")
        assertEquals(
            "v3/books/$bookKey/chapters/$chapterKey/manifest.json.gz",
            LibraryCloudPaths.v3ManifestPath(bookKey, chapterKey)
        )
        assertEquals(
            "v3/books/$bookKey/chapters/$chapterKey/sources/$sourceKey.json.gz",
            LibraryCloudPaths.v3PayloadPath(bookKey, chapterKey, sourceKey)
        )
    }

    @Test
    fun v3CurrentPathUsesOneObjectPerSharedChapter() {
        val book = Book(name = "廓晋", author = "榴弹怕水")
        val chapter = BookChapter(title = "第31章 婚姻")
        val bookKey = LibraryCloudKeys.sharedBookKey(book)
        val chapterKey = LibraryCloudKeys.libraryChapterKey(chapter)
        assertEquals(
            "v3/books/$bookKey/chapters/$chapterKey/current.json.gz",
            LibraryCloudPaths.v3CurrentPath(bookKey, chapterKey)
        )
    }

    @Test
    fun v3PayloadMatchesByFuzzyTitle() {
        val book = Book(name = "廓晋", author = "榴弹怕水")
        val chapter = BookChapter(title = " 第31章 婚姻")
        val payload = LibraryChapterPayloadV3(
            name = "廓晋",
            author = "榴弹怕水",
            title = "第三十一章 婚姻",
            relaxedTitle = LibraryCloudKeys.relaxedTitle("第三十一章 婚姻"),
            ordinalTitle = "31",
            content = "正文内容"
        )
        assertTrue(LibraryCloudKeys.payloadMatches(book, chapter, payload))
    }
}
