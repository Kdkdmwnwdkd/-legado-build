package io.legado.app.model.localBook.epubcore.template

import android.text.Layout
import io.legado.app.model.localBook.epubcore.direct.EpubDirectChapter
import io.legado.app.model.localBook.epubcore.direct.EpubDirectDocumentBuilder
import io.legado.app.model.localBook.epubcore.direct.TextReaderImageClickPolicy
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.help.config.AppConfig
import org.json.JSONObject
import splitties.init.appCtx
import java.util.Locale

/** The author document never shares an origin or a JavaScript global with the native host. */
object EpubTemplateDocument {
    private const val HOST_HTML = "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent}" +
        "iframe{position:absolute;inset:0;width:100%;height:100%;border:0;background:transparent}</style>" +
        "</head><body></body></html>"

    private val hostScript by lazy { asset("epub/template-host.js") }
    private fun asset(path: String): String = appCtx.assets.open(path)
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun wrap(chapter: EpubDirectChapter, sourceHtml: String, template: EpubReaderTemplate): EpubDirectChapter {
        require(chapter.sourceChapterUrl != null) { "页面模板仅用于普通正文的 EPUB 渲染" }
        require(template.validate().isEmpty()) { template.validate().joinToString("\n") }
        return chapter.copy(html = HOST_HTML, readerTemplate = template, templateSourceHtml = sourceHtml)
    }

    fun runtimeScript(token: Long, chapter: EpubDirectChapter, config: EpubCoreLayoutConfig, secret: String,
                      fieldsJson: String): String {
        val template = requireNotNull(chapter.readerTemplate)
        val density = appCtx.resources.displayMetrics.density.coerceAtLeast(1f)
        val init = JSONObject().apply {
            put("token", token)
            put("template", JSONObject(template.toJson()))
            put("sourceHtml", requireNotNull(chapter.templateSourceHtml))
            put("plainText", chapter.plainText)
            put("baseUrl", chapter.baseUrl)
            put("baseCss", baseCss(config, chapter.baseUrl, density) + "\n" +
                io.legado.app.help.reader.ReaderAssetReferences.fontCss(
                    template.firstPageHtml + "\n" + template.otherPageHtml + "\n" + template.css + "\n" + template.javascript))
            put("textImageMode", TextReaderImageClickPolicy.mode(AppConfig.clickImgWay, chapter.sourceImages))
            put("fields", JSONObject(fieldsJson).put("chapterTitle", chapter.title))
            put("viewport", JSONObject().put("width", config.pageWidthPx / density)
                .put("height", config.pageHeightPx / density))
            put("scrollMode", config.scrollMode)
            put("bottomJustify", config.textBottomJustify)
        }
        // Only the trusted host receives the secret. Author HTML/JS is carried as data,
        // not concatenated into host source, and is executed in a sandboxed srcdoc.
        return hostScript.replace("/*__READER_TEMPLATE_BOOTSTRAP__*/", JSONObject().apply {
            put("secret", secret)
            put("init", init)
        }.toString().replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"))
    }

    internal fun baseCss(config: EpubCoreLayoutConfig, baseUrl: String, density: Float): String {
        fun px(value: Number): String = String.format(Locale.ROOT, "%.3fpx", value.toFloat() / density)
        fun color(value: Int): String = "rgba(${value ushr 16 and 255},${value ushr 8 and 255},${value and 255}," +
            String.format(Locale.ROOT, "%.3f", (value ushr 24 and 255) / 255f) + ")"
        val fontUrl = EpubDirectDocumentBuilder.readerFontResourceUrl(
            resourceHost = java.net.URI(baseUrl).host.orEmpty(),
            readerFontUrl = config.readerFontUrl,
            readerFontRevision = config.readerFontRevision
        )
        val family = if (fontUrl != null) "'legado-reader-font',serif" else "serif"
        val fontFace = fontUrl?.let { "@font-face{font-family:'legado-reader-font';src:url('$it');font-display:block;}" }.orEmpty()
        val alignment = when (config.alignment) {
            Layout.Alignment.ALIGN_CENTER -> "center"
            Layout.Alignment.ALIGN_OPPOSITE -> "end"
            else -> if (config.textFullJustify) "justify" else "start"
        }
        // These are defaults, deliberately without !important. Template CSS follows
        // them and can replace the whole page structure and typography.
        return """
            $fontFace
            :root{--reader-text-color:${color(config.textPaint.color)};--reader-page-background:${if (config.readerBackgroundImage) "transparent" else color(config.backgroundColor)};--reader-accent:var(--reader-text-color);--reader-font-size:${px(config.textPaint.textSize)};--reader-line-height:${px(config.lineHeightPx)};--reader-paragraph-indent:${px(config.paragraphIndentPx)};--reader-paragraph-spacing:${px(config.paragraphSpacingPx)};--reader-padding-left:${px(config.readerPaddingLeftPx)};--reader-padding-top:${px(config.readerPaddingTopPx)};--reader-padding-right:${px(config.readerPaddingRightPx)};--reader-padding-bottom:${px(config.readerPaddingBottomPx)};--reader-safe-left:${px(config.readerSafeInsetLeftPx)};--reader-safe-top:${px(config.readerSafeInsetTopPx)};--reader-safe-right:${px(config.readerSafeInsetRightPx)};--reader-safe-bottom:${px(config.readerSafeInsetBottomPx)};}
            body{margin:0;color:var(--reader-text-color);font-size:var(--reader-font-size);line-height:var(--reader-line-height);font-family:$family;font-weight:${config.textFontWeight};font-style:${if (config.textFontItalic) "italic" else "normal"};letter-spacing:${config.textPaint.letterSpacing}em;text-align:$alignment;-webkit-text-size-adjust:none;overflow-wrap:break-word;}
            [data-reader-page]{padding:var(--reader-safe-top) var(--reader-safe-right) var(--reader-safe-bottom) var(--reader-safe-left);}
            .reader-paragraph{margin:0 0 var(--reader-paragraph-spacing);text-indent:var(--reader-paragraph-indent);orphans:2;widows:2;}
            [data-reader-continuation]{text-indent:0;}
            figure{margin:0 0 var(--reader-paragraph-spacing);text-align:center;}
            img,svg,video,canvas{max-width:100%;max-height:100%;object-fit:contain;}
            img{height:auto;}
            pre,code{white-space:pre-wrap;overflow-wrap:anywhere;}
            a[data-legado-image-action],img[data-legado-image-id]{-webkit-tap-highlight-color:transparent;}
            ::selection{background:${color(config.selectionColor)};}
        """.trimIndent()
    }
}
