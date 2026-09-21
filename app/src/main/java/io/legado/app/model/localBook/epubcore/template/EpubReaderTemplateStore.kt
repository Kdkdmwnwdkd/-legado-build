package io.legado.app.model.localBook.epubcore.template

import io.legado.app.help.config.ReadBookConfig
import splitties.init.appCtx
import java.io.File

object EpubReaderTemplateStore {
    const val fileName = "readerTemplates.json"
    const val singleTemplateFileName = "readerTemplate.json"
    val filePath: String get() = File(appCtx.filesDir, fileName).absolutePath
    val builtinIds = listOf("builtin.night", "builtin.vertical")
    private val retiredBuiltinIds = setOf("builtin.magazine", "builtin.clean", "builtin.garden", "builtin.cat")

    private val repository by lazy {
        EpubReaderTemplateRepository(File(filePath), retiredBuiltinIds) {
            builtinIds.map { id ->
                appCtx.assets.open("epub/templates/" + id + ".json")
                    .bufferedReader(Charsets.UTF_8).use { EpubReaderTemplate.fromJson(it.readText()) }
                    .also { check(it.id == id) { "内置模板 id 不匹配：" + id } }
            }
        }
    }

    @Synchronized
    fun list(): List<EpubReaderTemplate> {
        clearRetiredReferences()
        return repository.list()
    }

    @Synchronized
    fun resolve(id: String): EpubReaderTemplate? {
        if (id.isEmpty()) return null
        if (id in retiredBuiltinIds) clearRetiredReferences()
        return repository.resolve(id)
    }
    fun isBuiltIn(id: String): Boolean = id in builtinIds
    fun isRetiredBuiltIn(id: String): Boolean = id in retiredBuiltinIds
    @Synchronized
    fun save(template: EpubReaderTemplate): EpubReaderTemplate = repository.save(template)
    @Synchronized
    fun importJson(raw: String, asCopy: Boolean = true): EpubReaderTemplate = repository.importJson(raw, asCopy)
    fun exportJson(id: String): String = requireNotNull(resolve(id)) { "模板不存在：" + id }.toJson()
    fun copyOf(template: EpubReaderTemplate): EpubReaderTemplate = copyReaderTemplate(template)

    /** Selection and deletion use the same lock, so a late selection cannot restore a deleted ID. */
    @Synchronized
    fun saveSelection(id: String) {
        clearRetiredReferences()
        require(id.isEmpty() || repository.resolve(id) != null) { "模板不存在" }
        ReadBookConfig.saveReaderTemplateSelection(id)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (repository.resolve(id) == null) return false
        return ReadBookConfig.withoutReaderTemplateReferences(setOf(id)) { repository.delete(id) }
    }

    @Synchronized
    fun hasHiddenBuiltIns(): Boolean = repository.hasHiddenBuiltIns()

    @Synchronized
    fun restoreBuiltIns() = repository.restoreBuiltIns()

    @Synchronized
    internal fun importLibrary(library: EpubReaderTemplateLibrary): List<EpubReaderTemplate> {
        library.validate()
        return ReadBookConfig.withoutReaderTemplateReferences(library.hiddenBuiltInIds + retiredBuiltinIds) {
            repository.importLibrary(library)
        }
    }

    @Synchronized
    internal fun exportLibrary(): EpubReaderTemplateLibrary = repository.exportLibrary()

    /** Saves all user templates and exact source for built-ins referenced by layout profiles. */
    fun backupJson(referencedIds: Iterable<String>): String = repository.backupJson(referencedIds)
    @Synchronized
    fun restoreJson(raw: String) = repository.restoreJson(raw)

    /** A shared style must never overwrite another style's template with a colliding id. */
    @Synchronized
    fun importForLayout(raw: String, expectedId: String): EpubReaderTemplate = repository.importForLayout(raw, expectedId)

    private fun clearRetiredReferences() {
        ReadBookConfig.withoutReaderTemplateReferences(retiredBuiltinIds) { Unit }
    }
}
