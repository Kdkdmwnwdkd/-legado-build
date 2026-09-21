package io.legado.app.model.localBook.epubcore.facade

import android.graphics.Color
import android.text.Layout
import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

class EpubCurrencyExactProbeTest {
    @Test
    fun dumpCurrencyChapters() {
        val file = File("C:/Users/Admin/Pictures/货币战争全集.epub")
        assumeTrue(file.isFile)
        val cache = File("build/tmp/epub-currency-performance-exact").apply { mkdirs() }
        EpubCoreFacade.open(
            file = file,
            bookUrl = "file://${file.absolutePath}",
            bookCacheDir = cache,
            bookSignature = "currency-exact"
        ).use { facade ->
            val chapters = facade.chapters()
            val config = EpubCoreLayoutConfig(
                pageWidthPx = 1080,
                pageHeightPx = 1920,
                textPaint = object : TextPaint() {
                    override fun getColor(): Int = Color.BLACK
                    override fun getTextSize(): Float = 48f
                    override fun getLetterSpacing(): Float = 0f
                },
                backgroundColor = Color.WHITE,
                selectionColor = 0x14000000,
                alignment = Layout.Alignment.ALIGN_NORMAL,
                lineHeightPx = 58f
            )
            println("CURRENCY_CHAPTER_COUNT ${chapters.size}")
            chapters.take(12).forEach { c ->
                val start = System.nanoTime()
                val prepared = facade.prepareDirectChapter(c, config)
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                val out = File("output/currency-prepared-${c.index}.html")
                out.writeText(prepared.html)
                println("CURRENCY_CHAPTER index=${c.index} title=${c.title} url=${c.url} " +
                    "start=${c.startFragmentId} end=${c.endFragmentId} mode=${prepared.layoutMode} " +
                    "chars=${prepared.html.length} plain=${prepared.plainText.length} ms=$elapsed")
            }
        }
    }
}
