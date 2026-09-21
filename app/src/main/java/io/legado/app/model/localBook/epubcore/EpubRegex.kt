package io.legado.app.model.localBook.epubcore

/** Keeps an optional EPUB rule from poisoning its owning class during initialization. */
internal object EpubRegex {

    fun compile(pattern: String): Regex {
        return compile(pattern, emptySet())
    }

    fun compile(pattern: String, option: RegexOption): Regex {
        return compile(pattern, setOf(option))
    }

    fun compile(pattern: String, options: Set<RegexOption>): Regex {
        return try {
            Regex(pattern, options)
        } catch (_: LinkageError) {
            neverMatch()
        } catch (_: RuntimeException) {
            neverMatch()
        }
    }

    private fun neverMatch(): Regex = Regex("a\\A")
}
