package io.legado.app.constant

enum class PageAnimationSpeed(
    val preferenceValue: Int,
    val durationMillis: Int
) {
    EXTREME(0, 180),
    STANDARD(1, 300),
    RELAXED(2, 420),
    ELEGANT(3, 560);

    companion object {
        fun fromPreference(value: Int): PageAnimationSpeed {
            return entries.firstOrNull { it.preferenceValue == value } ?: STANDARD
        }
    }
}
