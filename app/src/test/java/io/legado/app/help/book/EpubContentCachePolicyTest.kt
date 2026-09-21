package io.legado.app.help.book

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubContentCachePolicyTest {

    @Test
    fun `rejects historical local epub failure body`() {
        assertTrue(
            EpubContentCachePolicy.isFailurePayload(
                isEpub = true,
                content = "获取本地书籍内容失败\nio.legado.app.model.localBook.epubcore.direct.EpubDirectSession is closed"
            )
        )
    }

    @Test
    fun `does not reject ordinary epub or non epub content`() {
        assertFalse(EpubContentCachePolicy.isFailurePayload(isEpub = true, content = "正常正文"))
        assertFalse(
            EpubContentCachePolicy.isFailurePayload(
                isEpub = false,
                content = "获取本地书籍内容失败\n正文中的普通句子"
            )
        )
    }

    @Test
    fun `text renderer refreshes native and legacy wrapped caches`() {
        assertTrue(
            EpubContentCachePolicy.needsRendererRefresh(
                isEpub = true,
                adaptSpecialStyle = true,
                useEpubCore = false,
                content = "<epub-native data-native-ver=\"2\" data-href=\"chapter.xhtml\" />"
            )
        )
        assertTrue(
            EpubContentCachePolicy.needsRendererRefresh(
                isEpub = true,
                adaptSpecialStyle = true,
                useEpubCore = false,
                content = "  <USEHTML><!--epub-text-ver=1-->旧正文</USEHTML>"
            )
        )
        assertFalse(
            EpubContentCachePolicy.needsRendererRefresh(
                isEpub = true,
                adaptSpecialStyle = true,
                useEpubCore = false,
                content = "正文\n<img src=\"images/a.png\">"
            )
        )
    }

    @Test
    fun `direct renderer still requires the complete native cache contract`() {
        assertTrue(
            EpubContentCachePolicy.needsRendererRefresh(
                isEpub = true,
                adaptSpecialStyle = true,
                useEpubCore = true,
                content = "<epub-native data-href=\"chapter.xhtml\" />"
            )
        )
        assertFalse(
            EpubContentCachePolicy.needsRendererRefresh(
                isEpub = true,
                adaptSpecialStyle = true,
                useEpubCore = true,
                content = "<epub-native data-native-ver=\"2\" data-href=\"chapter.xhtml\" />"
            )
        )
    }

    @Test
    fun `renderer cache migration is limited to styled epub content`() {
        assertFalse(
            EpubContentCachePolicy.needsRendererRefresh(
                isEpub = false,
                adaptSpecialStyle = true,
                useEpubCore = false,
                content = "<usehtml>普通网页正文</usehtml>"
            )
        )
        assertFalse(
            EpubContentCachePolicy.needsRendererRefresh(
                isEpub = true,
                adaptSpecialStyle = false,
                useEpubCore = false,
                content = "<usehtml>旧 EPUB 正文</usehtml>"
            )
        )
    }
}
