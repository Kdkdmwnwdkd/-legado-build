package io.legado.app.help.reader

/** Only replace the small CSS section created by the resource picker; retain author code. */
object ReaderTemplateAssetStyle {
    fun font(css: String, id: String?): String = replace(css, "font", id?.let {
        "body,[data-reader-flow]{font-family:'${ReaderAssetReferences.fontFamily(it)}' !important;}"
    })

    fun background(css: String, id: String?): String = replace(css, "background", id?.let {
        "[data-reader-page]{background-image:url('${ReaderAssetReferences.url(it)}');background-size:cover;background-position:center;}"
    })

    fun selected(css: String, kind: String): String? = section(kind).findAll(css).lastOrNull()?.value?.let {
        ReaderAssetReferences.ids(it).firstOrNull()
    }

    private fun section(kind: String) = Regex("^/\\* reader-assets:$kind \\*/\\r?\\n[\\s\\S]*?^/\\* /reader-assets:$kind \\*/", RegexOption.MULTILINE)
    private fun replace(css: String, kind: String, value: String?): String {
        val original = section(kind).replace(css, "")
        return if (value == null) original else original + "\n/* reader-assets:$kind */\n$value\n/* /reader-assets:$kind */"
    }
}
