package io.legado.app.model.localBook.epubcore.template

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EpubReaderTemplateRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun savedTemplateSurvivesReopenWithoutCodeChanges() {
        val file = target()
        val template = exampleTemplate().copy(css = "\r\n.x { color:red }  ")
        repository(file).save(template)
        assertEquals(template, repository(file).resolve(template.id))
        assertFalse(File(file.parentFile, "." + file.name + ".staging").exists())
        assertFalse(File(file.parentFile, "." + file.name + ".backup").exists())
    }

    @Test
    fun failedImportDoesNotOverwriteCommittedLibrary() {
        val file = target()
        val repository = repository(file)
        val template = exampleTemplate()
        repository.save(template)
        val committed = file.readBytes().toList()
        assertThrows(IllegalArgumentException::class.java) { repository.importJson("{broken") }
        assertThrows(IllegalArgumentException::class.java) {
            repository.importJson(template.copy(css = "changed").toJson(), asCopy = false)
        }
        assertEquals(committed, file.readBytes().toList())
        assertEquals(template, repository.resolve(template.id))
    }

    @Test
    fun copyingBuiltinKeepsAllCodeAndDoesNotModifyBuiltin() {
        val builtin = exampleTemplate("builtin.example")
        val repository = repository(target(), listOf(builtin))
        assertThrows(IllegalArgumentException::class.java) { repository.save(builtin.copy(css = "changed")) }
        val copy = repository.importJson(builtin.toJson())
        assertNotEquals(builtin.id, copy.id)
        assertEquals(builtin.copy(id = copy.id, name = copy.name), copy)
        assertEquals(builtin, repository.resolve(builtin.id))
    }

    @Test
    fun backupRestoresUsersAndExactSelectedBuiltinEvenAfterAppChanges() {
        val builtin = exampleTemplate("builtin.example")
        val user = exampleTemplate()
        val source = repository(target(), listOf(builtin)).apply { save(user) }
        val backup = source.backupJson(listOf(builtin.id))
        val destination = repository(target(), listOf(builtin.copy(css = "new bundled css")))
        destination.restoreJson(backup)
        assertEquals(user, destination.resolve(user.id))
        assertEquals(builtin, destination.resolve(builtin.id))
        assertEquals(2, destination.list().size)
    }

    @Test
    fun duplicateOrInvalidRestoreKeepsPreviousLibrary() {
        val file = target()
        val repository = repository(file).apply { save(exampleTemplate()) }
        val before = file.readText()
        val duplicate = JsonObject().apply {
            addProperty("schemaVersion", 1)
            add("templates", JsonArray().apply {
                repeat(2) { add(EpubReaderTemplate.json.toJsonTree(exampleTemplate())) }
            })
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { repository.restoreJson(duplicate) }
        assertThrows(IllegalArgumentException::class.java) { repository.restoreJson("{\"schemaVersion\":1,\"templates\":null}") }
        assertEquals(before, file.readText())
    }

    @Test
    fun corruptLibraryCannotBeSilentlyReplacedBySavingAnotherTemplate() {
        val file = target().apply { writeText("{corrupt") }
        val repository = repository(file)
        assertThrows(IllegalArgumentException::class.java) { repository.list() }
        assertThrows(IllegalArgumentException::class.java) { repository.save(exampleTemplate()) }
        assertEquals("{corrupt", file.readText())
    }

    @Test
    fun importedLayoutUsesCopyOnCollisionButReusesIdenticalTemplate() {
        val existing = exampleTemplate()
        val repository = repository(target()).apply { save(existing) }
        assertEquals(existing, repository.importForLayout(existing.toJson(), existing.id))
        val incoming = existing.copy(css = "different layout")
        val imported = repository.importForLayout(incoming.toJson(), incoming.id)
        assertNotEquals(existing.id, imported.id)
        assertEquals(incoming.copy(id = imported.id, name = imported.name), imported)
        assertEquals(existing, repository.resolve(existing.id))
        assertThrows(IllegalArgumentException::class.java) { repository.importForLayout(incoming.toJson(), "wrong-id") }
    }

    @Test
    fun interruptedAtomicCommitRecoversTheLastTemplateLibrary() {
        val file = target()
        val template = exampleTemplate()
        repository(file).save(template)
        val backup = File(file.parentFile, "." + file.name + ".backup")
        assertTrue(file.renameTo(backup))
        File(file.parentFile, "." + file.name + ".staging").writeText("unfinished")
        assertEquals(template, repository(file).resolve(template.id))
        assertTrue(file.isFile)
        assertFalse(backup.exists())
    }

    @Test
    fun concurrentSavesDoNotLoseEitherTemplate() {
        val repository = repository(target())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        listOf("first", "second").forEach { id ->
            Thread {
                try { start.await(); repository.save(exampleTemplate(id)) }
                catch (error: Throwable) { errors.add(error) }
                finally { done.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(setOf("first", "second"), repository.list().map { it.id }.toSet())
    }

    @Test
    fun deletingUserTemplatePersistsAndDoesNotRemoveOtherTemplates() {
        val file = target()
        val removed = exampleTemplate("user.removed")
        val kept = exampleTemplate("user.kept")
        val source = repository(file).apply { save(removed); save(kept) }
        assertTrue(source.delete(removed.id))
        assertFalse(source.delete(removed.id))
        assertEquals(listOf(kept), repository(file).list())
    }

    @Test
    fun removedBuiltinStaysHiddenAfterReopenAndCanBeRestoredWithItsSavedSource() {
        val file = target()
        val builtin = exampleTemplate("builtin.example")
        val oldSnapshot = builtin.copy(css = "old saved built-in CSS")
        val source = repository(file, listOf(builtin))
        source.restoreJson(EpubReaderTemplateLibrary(listOf(oldSnapshot)).toJson())
        assertTrue(source.delete(builtin.id))
        val reopened = repository(file, listOf(builtin))
        assertEquals(emptyList<EpubReaderTemplate>(), reopened.list())
        assertTrue(reopened.hasHiddenBuiltIns())
        reopened.restoreBuiltIns()
        assertFalse(reopened.hasHiddenBuiltIns())
        assertEquals(oldSnapshot, repository(file, listOf(builtin)).resolve(builtin.id))
    }

    @Test
    fun retiredBuiltinCannotBeRestoredButUserCopiesRemainAvailable() {
        val file = target()
        val retired = retiredTemplates()
        val retiredIds = retired.map { it.id }.toSet()
        val active = listOf(exampleTemplate("builtin.night"), exampleTemplate("builtin.vertical"))
        val copies = retired.map { template ->
            template.copy(id = "user." + template.id.removePrefix("builtin."),
                javascript = template.javascript + "\r\n// 用户编辑的副本\r\n")
        }
        repository(file, active + retired)
            .restoreJson(EpubReaderTemplateLibrary(retired + copies).toJson())
        val committed = file.readBytes().toList()
        val upgraded = EpubReaderTemplateRepository(file, retiredIds) { active }
        assertEquals(active + copies, upgraded.list())
        assertFalse(upgraded.hasHiddenBuiltIns())
        retired.forEach { assertNull(upgraded.resolve(it.id)) }
        upgraded.restoreBuiltIns()
        assertEquals(active + copies, upgraded.list())
        assertEquals(committed, file.readBytes().toList())
        assertEquals(active + copies, EpubReaderTemplateRepository(file, retiredIds) { active }.list())

        val backup = upgraded.exportLibrary()
        assertEquals(retiredIds, backup.hiddenBuiltInIds)
        val restored = EpubReaderTemplateRepository(target(), retiredIds) { active }
        restored.importLibrary(backup)
        restored.restoreBuiltIns()
        assertEquals(active + copies, restored.list())
        retired.forEach { assertNull(restored.resolve(it.id)) }
    }

    @Test
    fun explicitlyImportingRetiredLibrarySourcesCreatesEditableCopies() {
        val retired = retiredTemplates()
        val retiredIds = retired.map { it.id }.toSet()
        val active = listOf(exampleTemplate("builtin.night"), exampleTemplate("builtin.vertical"))
        val source = EpubReaderTemplateRepository(target(), retiredIds) { active }
        val imported = source.importLibrary(EpubReaderTemplateLibrary(retired))
        assertEquals(retired.size, imported.size)
        retired.zip(imported).forEach { (original, copy) ->
            assertTrue(copy.id.startsWith("user."))
            assertEquals(original.copy(id = copy.id, name = copy.name), copy)
            assertNull(source.resolve(original.id))
            assertEquals(copy, source.resolve(copy.id))
        }
        source.restoreBuiltIns()
        assertEquals(active + imported, source.list())
    }

    @Test
    fun importingLayoutWithRetiredSourcePreservesAllAuthorCode() {
        val file = target()
        val retired = retiredTemplates()
        val retiredIds = retired.map { it.id }.toSet()
        val active = listOf(exampleTemplate("builtin.night"), exampleTemplate("builtin.vertical"))
        val source = EpubReaderTemplateRepository(file, retiredIds) { active }
        val imported = retired.map { original ->
            source.importForLayout(original.toJson(), original.id).also { copy ->
                assertTrue(copy.id.startsWith("user."))
                assertEquals(original.copy(id = copy.id, name = copy.name), copy)
                assertNull(source.resolve(original.id))
            }
        }
        val reopened = EpubReaderTemplateRepository(file, retiredIds) { active }
        assertEquals(active + imported, reopened.list())
        imported.forEach { assertEquals(it, reopened.resolve(it.id)) }
    }

    @Test
    fun libraryImportMergesWithoutOverwritingConflictingCode() {
        val file = target()
        val old = exampleTemplate("user.existing")
        val new = exampleTemplate("user.new")
        val conflicting = old.copy(javascript = "console.log('keep every character');\r\n")
        val source = repository(file).apply { save(old) }
        val imported = source.importLibrary(EpubReaderTemplateLibrary(listOf(conflicting, new)))
        assertEquals(2, imported.size)
        assertNotEquals(old.id, imported[0].id)
        assertEquals(conflicting.copy(id = imported[0].id, name = imported[0].name), imported[0])
        assertEquals(old, source.resolve(old.id))
        assertEquals(new, source.resolve(new.id))
        assertEquals(3, repository(file).list().size)
    }

    @Test
    fun repeatedIdenticalLibraryImportIsIdempotent() {
        val file = target()
        val template = exampleTemplate()
        val source = repository(file)
        val incoming = EpubReaderTemplateLibrary(listOf(template))
        assertEquals(listOf(template), source.importLibrary(incoming))
        val committed = file.readBytes().toList()
        assertEquals(listOf(template), source.importLibrary(incoming))
        assertEquals(committed, file.readBytes().toList())
        assertEquals(listOf(template), source.list())
    }

    @Test
    fun invalidSecondLibraryEntryCannotCommitTheFirstEntry() {
        val file = target()
        val kept = exampleTemplate("kept")
        val source = repository(file).apply { save(kept) }
        val committed = file.readBytes().toList()
        assertThrows(IllegalArgumentException::class.java) {
            source.importLibrary(EpubReaderTemplateLibrary(listOf(
                exampleTemplate("new"), exampleTemplate("invalid").copy(firstPageHtml = "")
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            source.importLibrary(EpubReaderTemplateLibrary(listOf(exampleTemplate("duplicate"), exampleTemplate("duplicate"))))
        }
        assertEquals(committed, file.readBytes().toList())
        assertEquals(listOf(kept), source.list())
    }

    @Test
    fun independentLibraryBackupKeepsHiddenBuiltinAndUserSource() {
        val builtin = exampleTemplate("builtin.example")
        val user = exampleTemplate().copy(javascript = "function arbitrary(){return '\uD83D\uDC31';}\r\n")
        val source = repository(target(), listOf(builtin)).apply { save(user); delete(builtin.id) }
        val target = repository(target(), listOf(builtin))
        target.importLibrary(source.exportLibrary())
        assertTrue(target.hasHiddenBuiltIns())
        assertEquals(listOf(user), target.list())
        target.restoreBuiltIns()
        assertEquals(builtin, target.resolve(builtin.id))
    }

    @Test
    fun importingStyleWithHiddenBuiltinMakesAUserCopyWithoutRevivingTheBuiltin() {
        val builtin = exampleTemplate("builtin.example")
        val source = repository(target(), listOf(builtin)).apply { delete(builtin.id) }
        val imported = source.importForLayout(builtin.toJson(), builtin.id)
        assertTrue(source.hasHiddenBuiltIns())
        assertNotEquals(builtin.id, imported.id)
        assertEquals(builtin.copy(id = imported.id, name = imported.name), imported)
        assertEquals(listOf(imported), source.list())
    }

    @Test
    fun invalidHiddenBuiltinStateDoesNotReplaceSavedLibrary() {
        val file = target()
        val source = repository(file).apply { save(exampleTemplate()) }
        val committed = file.readText()
        listOf(
            "{\"schemaVersion\":1,\"templates\":[],\"hiddenBuiltInIds\":[\"user.kept\"]}",
            "{\"schemaVersion\":1,\"templates\":[],\"hiddenBuiltInIds\":[\"builtin.example\",\"builtin.example\"]}",
            "{\"schemaVersion\":1,\"templates\":[],\"hiddenBuiltInIds\":null}"
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { source.restoreJson(invalid) }
        }
        assertEquals(committed, file.readText())
    }

    private fun retiredTemplates(): List<EpubReaderTemplate> =
        listOf("clean", "garden", "cat", "magazine").map { name ->
            exampleTemplate("builtin.$name").copy(
                name = "$name 旧模板",
                firstPageHtml = "\r\n<custom-page onclick=\"show('$name')\"><main data-reader-flow=\"body\"></main></custom-page>  \n",
                otherPageHtml = "<section data-author=\"$name\"><main data-reader-flow=\"body\"></main></section>\r\n",
                css = "/* $name 留白 */\r\n@supports (display: grid) { .page { display: grid; shape-outside: circle(); --author: '　'; } }  \n",
                javascript = "\r\n// $name 🌙\r\nconst ready = Promise.resolve('<&>'); readerTemplate.ready(ready);\n"
            )
        }

    private fun target(): File = File(temporaryFolder.newFolder(), "readerTemplates.json")
    private fun repository(file: File, builtins: List<EpubReaderTemplate> = emptyList()) =
        EpubReaderTemplateRepository(file) { builtins }
}
