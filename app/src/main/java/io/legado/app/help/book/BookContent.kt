package io.legado.app.help.book

import io.legado.app.data.entities.ReplaceRule

data class BookContent(
    val sameTitleRemoved: Boolean,
    val textList: List<String>,
    //起效的替换规则
    val effectiveReplaceRules: List<ReplaceRule>?,
    val sourceIndexes: List<Int> = textList.indices.toList(),
    // Direct rendering and read aloud share the title produced with this body.
    val displayTitle: String? = null
) {

    override fun toString(): String {
        return textList.joinToString("\n")
    }

}
