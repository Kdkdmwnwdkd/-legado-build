package io.legado.app.ui.association

import java.util.Locale

sealed interface OnlinePackageImportRoute {

    data class ParagraphRule(val sourceUrl: String) : OnlinePackageImportRoute

    data class Bubble(val sourceUrl: String) : OnlinePackageImportRoute

    data class AutoTask(val sourceUrl: String) : OnlinePackageImportRoute

    data class Invalid(val reason: String) : OnlinePackageImportRoute

    data object Other : OnlinePackageImportRoute

    companion object {
        private val supportedSchemes = setOf("legado", "yuedu")
        private val paragraphPaths = setOf("/paragraphrule", "/paragraphrules")
        // `/auto` is the long-standing generic import route.  It must fall
        // through to OnLineImportActivity's content-type detection.  Reserve
        // only explicit auto-task paths for the task importer so book-source
        // links such as legado://import/auto?src=... keep their old meaning.
        private val autoTaskPaths = setOf("/autotask", "/autotasks")

        fun parse(
            scheme: String?,
            host: String?,
            path: String?,
            sourceUrl: String?
        ): OnlinePackageImportRoute {
            if (scheme?.lowercase(Locale.ROOT) !in supportedSchemes) return Other
            val normalizedPath = path?.lowercase(Locale.ROOT) ?: return Other
            val target = when (normalizedPath) {
                in paragraphPaths -> Target.PARAGRAPH_RULE
                in autoTaskPaths -> Target.AUTO_TASK
                "/bubble" -> Target.LEGACY_BUBBLE
                "/bubblepackage" -> Target.BUBBLE
                else -> return Other
            }
            if (target != Target.LEGACY_BUBBLE && !host.equals("import", ignoreCase = true)) {
                return Invalid("Import link host must be import")
            }
            val source = sourceUrl.orEmpty()
            if (source.isBlank()) {
                return Invalid("Import link is missing src")
            }
            return when (target) {
                Target.PARAGRAPH_RULE -> ParagraphRule(source)
                Target.AUTO_TASK -> AutoTask(source)
                Target.BUBBLE,
                Target.LEGACY_BUBBLE -> Bubble(source)
            }
        }

        private enum class Target {
            PARAGRAPH_RULE,
            AUTO_TASK,
            BUBBLE,
            LEGACY_BUBBLE
        }
    }
}
