package io.legado.app.model.localBook.epubcore.template

import io.legado.app.help.config.AtomicTextFileStore
import java.io.File
import java.util.UUID

/** File-backed, JVM-testable storage. A corrupt document never becomes an empty template library. */
internal class EpubReaderTemplateRepository(
    private val file: File,
    private val retiredBuiltInIds: Set<String> = emptySet(),
    private val loadBuiltIns: () -> List<EpubReaderTemplate>
) {
    private val atomicFile = AtomicTextFileStore(file)
    private val builtIns by lazy { loadBuiltIns().also { EpubReaderTemplateLibrary(it).validate() } }

    @Synchronized
    fun list(): List<EpubReaderTemplate> {
        val library = readStored()
        return effectiveTemplates(library).filterNot {
            it.id in library.hiddenBuiltInIds || it.id in retiredBuiltInIds
        }
    }

    @Synchronized
    fun resolve(id: String): EpubReaderTemplate? = list().firstOrNull { it.id == id }

    @Synchronized
    fun save(template: EpubReaderTemplate): EpubReaderTemplate {
        EpubReaderTemplateLibrary(listOf(template)).validate()
        require(!template.id.startsWith("builtin.")) { "请先复制内置模板" }
        val library = readStored()
        val stored = library.templates.toMutableList()
        val index = stored.indexOfFirst { it.id == template.id }
        if (index < 0) stored.add(template) else stored[index] = template
        writeStored(library.copy(templates = stored))
        return template
    }

    @Synchronized
    fun importJson(raw: String, asCopy: Boolean = true): EpubReaderTemplate {
        val imported = EpubReaderTemplate.fromJson(raw)
        val template = if (asCopy) copyReaderTemplate(imported) else imported
        require(resolve(template.id) == null) { "模板 id 已存在；请选择导入为副本" }
        return save(template)
    }

    /** Validate the complete incoming library before making a single atomic commit. */
    @Synchronized
    fun importLibrary(imported: EpubReaderTemplateLibrary): List<EpubReaderTemplate> {
        imported.validate()
        val original = readStored()
        val stored = original.templates.associateByTo(linkedMapOf()) { it.id }
        val existing = effectiveTemplates(original).associateByTo(linkedMapOf()) { it.id }
        val hidden = (original.hiddenBuiltInIds + imported.hiddenBuiltInIds + retiredBuiltInIds).toMutableSet()
        val accepted = arrayListOf<EpubReaderTemplate>()
        imported.templates.forEach { template ->
            // A retired bundled snapshot in a library backup stays retired. An explicit
            // single-template import can still be copied to a user template by its caller.
            if (template.id in retiredBuiltInIds && template.id in imported.hiddenBuiltInIds) {
                return@forEach
            }
            val current = existing[template.id]
            val selected = when {
                current != null && current == template && template.id !in retiredBuiltInIds -> current
                current != null || template.id.startsWith("builtin.") -> copyReaderTemplate(template)
                else -> template
            }
            if (selected !== current) {
                require(selected.id !in existing) { "模板重复：" + selected.id }
                stored[selected.id] = selected
                existing[selected.id] = selected
            }
            if (selected.id == template.id && template.id !in imported.hiddenBuiltInIds) {
                hidden.remove(template.id)
            }
            accepted.add(selected)
        }
        val updated = EpubReaderTemplateLibrary(stored.values.toList(), hidden)
        if (updated != original) writeStored(updated)
        return accepted.filterNot { it.id in hidden || it.id in retiredBuiltInIds }.distinctBy { it.id }
    }

    @Synchronized
    fun importForLayout(raw: String, expectedId: String): EpubReaderTemplate {
        val imported = EpubReaderTemplate.fromJson(raw)
        require(imported.id == expectedId) { "阅读样式中的模板 id 与模板文件不一致" }
        val existing = resolve(imported.id)
        if (existing == imported) return existing
        return save(if (existing != null || imported.id.startsWith("builtin.")) copyReaderTemplate(imported) else imported)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val original = readStored()
        val isBuiltIn = builtIns.any { it.id == id } || id in retiredBuiltInIds
        if (!isBuiltIn && original.templates.none { it.id == id }) return false
        if (isBuiltIn && id in original.hiddenBuiltInIds) return false
        writeStored(original.copy(
            templates = if (isBuiltIn) original.templates else original.templates.filterNot { it.id == id },
            hiddenBuiltInIds = if (isBuiltIn) original.hiddenBuiltInIds + id else original.hiddenBuiltInIds
        ))
        return true
    }

    @Synchronized
    fun hasHiddenBuiltIns(): Boolean = readStored().hiddenBuiltInIds.any { id -> builtIns.any { it.id == id } }

    @Synchronized
    fun restoreBuiltIns() {
        val original = readStored()
        val updated = original.copy(hiddenBuiltInIds = original.hiddenBuiltInIds - builtIns.map { it.id }.toSet())
        if (updated != original) writeStored(updated)
    }

    /** An independent library backup includes the exact source of all available templates. */
    @Synchronized
    fun exportLibrary(): EpubReaderTemplateLibrary {
        val stored = readStored()
        return stored.copy(templates = effectiveTemplates(stored), hiddenBuiltInIds = stored.hiddenBuiltInIds + retiredBuiltInIds)
    }

    @Synchronized
    fun backupJson(referencedIds: Iterable<String>): String {
        val library = readStored()
        val stored = library.templates.associateByTo(linkedMapOf()) { it.id }
        referencedIds.filter { it.isNotEmpty() }.distinct().forEach { id ->
            if (id !in stored) {
                stored[id] = requireNotNull(builtIns.firstOrNull { it.id == id }) {
                    "模板不存在，无法完整备份：" + id
                }
            }
        }
        return library.copy(templates = stored.values.toList()).toJson()
    }

    @Synchronized
    fun restoreJson(raw: String) {
        // Built-in snapshots in backups intentionally override bundled versions with the same id.
        val restored = EpubReaderTemplateLibrary.fromJson(raw)
        writeStored(restored)
    }

    private fun readStored(): EpubReaderTemplateLibrary {
        atomicFile.recoverInterruptedCommit()
        return if (file.exists()) EpubReaderTemplateLibrary.fromJson(file.readText(Charsets.UTF_8)) else EpubReaderTemplateLibrary()
    }

    private fun writeStored(library: EpubReaderTemplateLibrary) {
        library.validate()
        atomicFile.writeVerified(library.toJson()) { source -> EpubReaderTemplateLibrary.fromJson(source) == library }
    }

    private fun effectiveTemplates(library: EpubReaderTemplateLibrary): List<EpubReaderTemplate> {
        val stored = library.templates.associateBy { it.id }
        val packagedIds = builtIns.map { it.id }.toSet()
        return builtIns.map { stored[it.id] ?: it } + stored.values.filter { it.id !in packagedIds }
    }
}

internal fun copyReaderTemplate(template: EpubReaderTemplate): EpubReaderTemplate = template.copy(
    id = "user." + UUID.randomUUID().toString(),
    name = template.name + "（副本）"
)
