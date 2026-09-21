package io.legado.app.model

/**
 * Keeps speculative adjacent-chapter loads from querying impossible DAO indexes.
 * A zero count means the chapter list is not known yet, so non-negative indexes
 * are still allowed to reach the normal missing-chapter error path.
 */
internal object ReadBookChapterIndexPolicy {

    fun canLoad(index: Int, knownChapterCount: Int): Boolean {
        return index >= 0 && (knownChapterCount <= 0 || index < knownChapterCount)
    }

    fun canSelect(index: Int, chapterCount: Int): Boolean {
        return index in 0 until chapterCount
    }
}
