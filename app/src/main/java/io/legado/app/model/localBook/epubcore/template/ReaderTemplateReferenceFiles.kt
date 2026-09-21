package io.legado.app.model.localBook.epubcore.template

import io.legado.app.help.config.AtomicTextFileStore
import java.io.File

/** Commit references first, then the library; interruption can never point at an already deleted ID. */
internal object ReaderTemplateReferenceFiles {
    fun <T> withoutReferences(replacements: Map<File, String>, commitLibrary: () -> T): T {
        val previous = replacements.keys.associateWith { file ->
            AtomicTextFileStore(file).recoverInterruptedCommit()
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        }
        val written = arrayListOf<File>()
        try {
            replacements.forEach { (file, json) ->
                AtomicTextFileStore(file).writeVerified(json) { it == json }
                written.add(file)
            }
            return commitLibrary()
        } catch (error: Exception) {
            written.asReversed().forEach { file ->
                try {
                    val original = previous[file]
                    val store = AtomicTextFileStore(file)
                    if (original == null) store.delete() else store.writeVerified(original) { it == original }
                } catch (restoreError: Exception) {
                    error.addSuppressed(restoreError)
                }
            }
            throw error
        }
    }
}
