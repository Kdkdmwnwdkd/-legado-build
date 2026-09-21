package io.legado.app.ui.book.read.epub

import kotlin.math.abs

internal object EpubDirectSnapshotVisualPolicy {

    fun hasVisualContent(backgroundColor: Int, sampledColors: IntArray): Boolean {
        return sampledColors.any { color -> !colorsNearlyEqual(color, backgroundColor) }
    }

    private fun colorsNearlyEqual(first: Int, second: Int): Boolean {
        return abs((first shr 16 and 0xff) - (second shr 16 and 0xff)) <= CHANNEL_TOLERANCE &&
            abs((first shr 8 and 0xff) - (second shr 8 and 0xff)) <= CHANNEL_TOLERANCE &&
            abs((first and 0xff) - (second and 0xff)) <= CHANNEL_TOLERANCE
    }

    private const val CHANNEL_TOLERANCE = 12
}
