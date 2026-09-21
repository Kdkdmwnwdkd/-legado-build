package io.legado.app.help.book.highlight

import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.model.localBook.epubcore.direct.EpubDirectResource
import io.legado.app.model.localBook.epubcore.direct.EpubDirectSession
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

object HighlightRules {
    const val BACKUP_DIR = "highlightRules"
    val store by lazy { HighlightRuleStore(File(appCtx.filesDir, BACKUP_DIR), ReaderAssets.store) }

    fun palette(config: EpubCoreLayoutConfig? = null) = HighlightStyle.Palette(
        text = cssColor(config?.textPaint?.color ?: ReadBookConfig.textColor),
        accent = cssColor(ReadBookConfig.textAccentColor),
        background = cssColor(config?.backgroundColor ?: ReadBookConfig.bgMeanColor),
        night = AppConfig.isNightTheme
    )

    fun decorate(html: String, bookUrl: String, styled: Boolean, host: String, config: EpubCoreLayoutConfig): String =
        HighlightDocument.apply(html, store.active(bookUrl, styled), palette(config)) { id ->
            EpubDirectSession.baseUrl("highlight-asset/" + id, host)
        }

    fun resource(path: String, headOnly: Boolean = false): EpubDirectResource? {
        if (!path.startsWith("highlight-asset/")) return null
        val id = path.removePrefix("highlight-asset/")
        val file = store.assetFile(id) ?: return ReaderAssets.resource(
            io.legado.app.help.reader.ReaderAssetReferences.URL_PREFIX + id, headOnly)
        val prefix = file.inputStream().use { input ->
            val bytes = ByteArray(24)
            val count = input.read(bytes)
            if (count > 0) bytes.copyOf(count) else byteArrayOf()
        }
        val mime = HighlightPackageParser.imageMime(prefix) ?: return null
        return EpubDirectResource(
            mimeType = mime, encoding = null, statusCode = 200, reasonPhrase = "OK",
            headers = mapOf("Content-Length" to file.length().toString(), "Cache-Control" to "private, max-age=31536000",
                "Access-Control-Allow-Origin" to "*"),
            stream = if (headOnly) ByteArrayInputStream(byteArrayOf()) else file.inputStream()
        )
    }

    private fun cssColor(value: Int): String = String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)",
        value ushr 16 and 255, value ushr 8 and 255, value and 255, (value ushr 24 and 255) / 255.0)
}
