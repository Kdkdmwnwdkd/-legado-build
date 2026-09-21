package io.legado.app.model.localBook.epubcore.facade

import org.junit.Assume.assumeTrue
import org.junit.Test
import android.graphics.Color
import android.text.Layout
import android.text.TextPaint
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import java.io.File

class EpubCurrencyPerformanceProbeTest {

    @Test
    fun measureCurrencyWarStages() {
        val file = File("C:/Users/Admin/Pictures").listFiles()
            ?.firstOrNull { it.name.endsWith(".epub", ignoreCase = true) && it.name.contains("战争") }
            ?: File("C:/Users/Admin/Pictures/currency-war.epub")
        assumeTrue(file.isFile)
        val cache = File("build/tmp/epub-currency-performance").apply { mkdirs() }
        repeat(2) { pass ->
            var facade: EpubCoreFacade? = null
            val openStart = System.nanoTime()
            try {
                facade = EpubCoreFacade.open(
                    file = file,
                    bookUrl = "file://${file.absolutePath}",
                    bookCacheDir = cache,
                    bookSignature = "currency-performance-$pass"
                )
                val openMs = elapsedMs(openStart)
                val chaptersStart = System.nanoTime()
                val chapters = facade.chapters()
                val chaptersMs = elapsedMs(chaptersStart)
                val coverStart = System.nanoTime()
                val cover = facade.coverResource()
                val coverMs = elapsedMs(coverStart)
                val first = chapters.firstOrNull { !it.url.startsWith("skip:") }
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
                val candidates = listOfNotNull(
                    chapters.firstOrNull { !it.url.startsWith("skip:") },
                    chapters.firstOrNull { it.url.contains("#", true) && !it.url.startsWith("skip:") },
                    chapters.firstOrNull { !it.url.startsWith("skip:") && it.index > 100 },
                    chapters.firstOrNull { !it.url.startsWith("skip:") && it.index > 300 }
                ).distinctBy { it.index }
                candidates.forEach { candidate ->
                    val prepStart = System.nanoTime()
                    val prepared = facade.prepareDirectChapter(candidate, config)
                    val prepMs = elapsedMs(prepStart)
                    println(
                        "CURRENCY_PREP pass=$pass index=${candidate.index} url=${candidate.url.take(180)} " +
                            "prepMs=$prepMs htmlChars=${prepared.html.length} plainChars=${prepared.plainText.length} " +
                            "mode=${prepared.layoutMode} start=${prepared.startFragmentId} end=${prepared.endFragmentId}"
                    )
                    val dump = File("output/currency-prepared-${candidate.index}.html")
                    dump.parentFile?.mkdirs()
                    dump.writeText(prepared.html)
                    println("CURRENCY_DUMP index=${candidate.index} path=${dump.absolutePath}")
                }
                println(
                    "CURRENCY_PERF pass=$pass openMs=$openMs chaptersMs=$chaptersMs " +
                        "chapters=${chapters.size} coverMs=$coverMs coverBytes=${cover?.bytes?.size ?: 0} " +
                        "first=${first?.url}"
                )
            } finally {
                facade?.close()
            }
        }
    }

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000L
}
