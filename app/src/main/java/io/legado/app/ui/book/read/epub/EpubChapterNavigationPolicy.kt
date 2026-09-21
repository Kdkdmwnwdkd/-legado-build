package io.legado.app.ui.book.read.epub

internal object EpubChapterNavigationPolicy {

    enum class Intent {
        ExplicitChapterJump,
        PageTurnBoundary
    }

    enum class TargetEdge {
        Start,
        End
    }

    data class Target(
        val chapterDelta: Int,
        val edge: TargetEdge,
        val boundaryTransition: Boolean
    )

    fun resolve(direction: Int, intent: Intent): Target? {
        if (direction == 0) return null
        val edge = when (intent) {
            Intent.ExplicitChapterJump -> TargetEdge.Start
            Intent.PageTurnBoundary -> if (direction > 0) TargetEdge.Start else TargetEdge.End
        }
        return Target(
            chapterDelta = direction,
            edge = edge,
            boundaryTransition = intent == Intent.PageTurnBoundary
        )
    }
}
