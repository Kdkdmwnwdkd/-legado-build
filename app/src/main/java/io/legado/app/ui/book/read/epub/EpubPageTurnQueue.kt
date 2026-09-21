package io.legado.app.ui.book.read.epub

import java.util.ArrayDeque

internal class EpubPageTurnQueue(
    private val maxRuns: Int,
    private val maxTurns: Int = DEFAULT_MAX_TURNS
) {

    private data class Run(
        val logicalDirection: Int,
        var count: Int
    )

    private val runs = ArrayDeque<Run>()
    private var pendingTurns = 0

    init {
        require(maxRuns > 0)
        require(maxTurns > 0)
    }

    val isEmpty: Boolean
        get() = runs.isEmpty()

    val runCount: Int
        get() = runs.size

    fun enqueue(logicalDirection: Int): Boolean {
        if (pendingTurns >= maxTurns) return false
        val direction = normalize(logicalDirection)
        val tail = runs.peekLast()
        if (tail?.logicalDirection == direction && tail.count < Int.MAX_VALUE) {
            tail.count++
            pendingTurns++
            return true
        }
        if (runs.size >= maxRuns) return false
        runs.addLast(Run(direction, count = 1))
        pendingTurns++
        return true
    }

    fun peekDirection(): Int? = runs.peekFirst()?.logicalDirection

    fun markHeadHandled(): Boolean {
        val head = runs.peekFirst() ?: return false
        head.count--
        pendingTurns--
        if (head.count <= 0) runs.removeFirst()
        return true
    }

    fun clear() {
        runs.clear()
        pendingTurns = 0
    }

    private fun normalize(logicalDirection: Int): Int {
        return if (logicalDirection < 0) -1 else 1
    }

    private companion object {
        const val DEFAULT_MAX_TURNS = 24
    }
}
