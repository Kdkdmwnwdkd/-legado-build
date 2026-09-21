package io.legado.app.help.book.highlight

import androidx.annotation.Keep
import java.util.UUID

/** Images are content-addressed files, never base64 fields in the rule index. */
@Keep
data class HighlightRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val keyword: String = "",
    val isRegex: Boolean = false,
    val isMultiline: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val global: Boolean = true,
    val bookUrl: String? = null,
    val sourceBookId: String? = null,
    val titleOnly: Boolean = false,
    val applyToStyledBooks: Boolean = true,
    val groupName: String = "",
    val styleType: String = "textColor",
    val styleColorType: String = "accent",
    val styleMode: String = "",
    val styleCssText: String = "",
    val asset: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val importWarning: String = ""
) {
    fun displayName(): String = name.ifBlank { keyword.take(60).ifBlank { "标题高亮" } }
    fun appliesTo(url: String, styled: Boolean): Boolean =
        enabled && (global || bookUrl == url) && (!styled || applyToStyledBooks)
}

data class HighlightPackage(
    val rules: List<HighlightRule>,
    val assets: Map<String, ByteArray>,
    val warnings: List<String> = emptyList(),
    val resources: List<io.legado.app.help.reader.ReaderAssetPayload> = emptyList()
)
