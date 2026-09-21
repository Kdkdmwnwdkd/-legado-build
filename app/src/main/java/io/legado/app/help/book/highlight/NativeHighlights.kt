package io.legado.app.help.book.highlight

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.text.style.MetricAffectingSpan
import androidx.core.graphics.toColorInt
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssets
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn

/** Native paint spans share the matcher; complex box/image CSS is handled by the Direct renderer. */
class NativeHighlights(rules: List<HighlightRule>, palette: HighlightStyle.Palette) {
    private val matcher = HighlightMatcher(rules)
    private val styles = rules.associate { rule ->
        rule.id to HighlightStyle.declarations(rule, palette) { "" }
    }
    private val fonts = rules.flatMap { ReaderAssetReferences.fontIds(it.styleCssText) }.distinct()
        .associateWith { ReaderAssets.typeface(it) }
    private fun typeface(style: Map<String, String>): Typeface? =
        ReaderAssetReferences.fontIds(style["font-family"].orEmpty()).firstNotNullOfOrNull { fonts[it] }
    data class Run(val start: Int, val end: Int, val style: Map<String, String>)
    data class Prepared(val text: CharSequence, val runs: List<Run>, val metricsChanged: Boolean, val scale: Float)

    fun prepare(text: String, title: Boolean, paint: TextPaint): Prepared {
        val matches = matcher.matches(text, title)
        if (matches.isEmpty()) return Prepared(text, emptyList(), false, 1f)
        val cuts = (listOf(0, text.length) + matches.flatMap { listOf(it.start, it.end) }).distinct().sorted()
        val runs = cuts.zipWithNext().mapNotNull { (start, end) ->
            val active = matches.filter { it.start <= start && it.end >= end }
            if (active.isEmpty()) null else Run(start, end, buildMap {
                active.forEach { putAll(styles[it.rule.id].orEmpty()) }
            })
        }
        val spanned = SpannableStringBuilder(text)
        var changed = false
        var scale = 1f
        runs.forEach { run ->
            typeface(run.style)?.let { face ->
                spanned.setSpan(ResourceFontSpan(face), run.start, run.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                changed = true
                val metrics = TextPaint(paint).apply {
                    typeface = face
                    textSize = textSize(run.style, paint.textSize)
                }.fontMetrics
                val base = paint.fontMetrics
                scale = maxOf(scale, ((metrics.bottom - metrics.top) / (base.bottom - base.top).coerceAtLeast(1f)).coerceIn(1f, 3f))
            }
            val size = textSize(run.style, paint.textSize)
            if (size != paint.textSize) {
                spanned.setSpan(AbsoluteSizeSpan(size.toInt()), run.start, run.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                changed = true
                scale = maxOf(scale, size / paint.textSize)
            }
            val style = fontStyle(run.style)
            if (style != Typeface.NORMAL) {
                spanned.setSpan(StyleSpan(style), run.start, run.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                changed = true
            }
        }
        return Prepared(if (changed) spanned else text, runs, changed, scale)
    }

    fun measure(prepared: Prepared, paint: TextPaint, widths: FloatArray) {
        if (!prepared.metricsChanged) return
        val text = prepared.text.toString()
        prepared.runs.forEach { run ->
            val copy = TextPaint(paint)
            copy.textSize = textSize(run.style, paint.textSize)
            val combinedStyle = (paint.typeface?.style ?: Typeface.NORMAL) or fontStyle(run.style)
            copy.typeface = Typeface.create(typeface(run.style) ?: paint.typeface, when (combinedStyle) {
                Typeface.BOLD -> Typeface.BOLD
                Typeface.ITALIC -> Typeface.ITALIC
                Typeface.BOLD_ITALIC -> Typeface.BOLD_ITALIC
                else -> Typeface.NORMAL
            })
            val measured = FloatArray(run.end - run.start)
            copy.getTextWidths(text, run.start, run.end, measured)
            measured.copyInto(widths, run.start)
        }
    }

    fun apply(line: TextLine, lineStart: Int, prepared: Prepared, paint: TextPaint) {
        if (prepared.runs.isEmpty()) return
        var offset = lineStart
        line.mapColumns { column ->
            val start = offset
            offset += (column as? TextBaseColumn)?.charData?.length ?: 1
            val run = prepared.runs.lastOrNull { start < it.end && offset > it.start }
            if (run == null || column !is TextBaseColumn) column
            else decorate(column, run.style, paint)
        }
    }

    private fun decorate(column: TextBaseColumn, style: Map<String, String>, paint: TextPaint): BaseColumn {
        val old = column as? TextHtmlColumn
        if (old == null && column !is TextColumn) return column
        val fontStyle = fontStyle(style)
        val decoration = style["text-decoration-line"] ?: style["text-decoration"].orEmpty()
        return TextHtmlColumn(
            start = column.start, end = column.end, charData = column.charData,
            mTextSize = old?.mTextSize ?: textSize(style, paint.textSize),
            mTextColor = parseColor(style["color"]) ?: old?.mTextColor,
            linkUrl = old?.linkUrl,
            isBold = fontStyle and Typeface.BOLD != 0 || old?.isBold == true,
            isItalic = fontStyle and Typeface.ITALIC != 0 || old?.isItalic == true,
            isUnderline = decoration.contains("underline") || old?.isUnderline == true,
            isStrikethrough = decoration.contains("line-through") || old?.isStrikethrough == true,
            backgroundColor = parseColor(style["background-color"]) ?: old?.backgroundColor,
            htmlTypeface = typeface(style) ?: old?.htmlTypeface ?: paint.typeface
        )
    }

    private class ResourceFontSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(tp: TextPaint) { tp.typeface = Typeface.create(face, tp.typeface?.style ?: Typeface.NORMAL) }
    }

    companion object {
        private fun fontStyle(style: Map<String, String>): Int =
            (if (style["font-weight"] == "bold" || (style["font-weight"]?.toIntOrNull() ?: 0) >= 600) Typeface.BOLD else 0) or
                (if (style["font-style"] in setOf("italic", "oblique")) Typeface.ITALIC else 0)

        private fun textSize(style: Map<String, String>, base: Float): Float {
            val value = style["font-size"] ?: return base
            val number = Regex("^[0-9.]+").find(value)?.value?.toFloatOrNull() ?: return base
            val size = when {
                value.endsWith("em") -> base * number
                value.endsWith("%") -> base * number / 100f
                value.endsWith("px") -> number * splitties.init.appCtx.resources.displayMetrics.density
                else -> base
            }
            return size.coerceIn(base * .5f, base * 3f)
        }

        fun parseColor(css: String?): Int? {
            css ?: return null
            return runCatching {
                if (css.startsWith("rgb")) {
                    val values = css.substringAfter('(').substringBefore(')').split(',').map { it.trim().toFloat() }
                    require(values.size in 3..4)
                    Color.argb(((values.getOrNull(3) ?: 1f).coerceIn(0f, 1f) * 255).toInt(),
                        values[0].toInt().coerceIn(0, 255), values[1].toInt().coerceIn(0, 255), values[2].toInt().coerceIn(0, 255))
                } else if (css.startsWith("#") && css.length == 9) {
                    ("#" + css.takeLast(2) + css.substring(1, 7)).toColorInt()
                } else css.toColorInt()
            }.getOrNull()
        }
    }
}
