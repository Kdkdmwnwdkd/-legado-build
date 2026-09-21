package io.legado.app.help.book.highlight

import androidx.annotation.Keep
import com.google.gson.Gson
import io.legado.app.help.reader.ReaderAssetReferences
import io.legado.app.help.reader.ReaderAssetStore
import java.io.File
import java.io.FileOutputStream

/** Small atomic JSON index plus deduplicated assets; independent of Room's CursorWindow limit. */
class HighlightRuleStore(val directory: File, private val sharedAssets: ReaderAssetStore? = null) {
    @Keep
    private data class Index(val version: Int = 1, val rules: List<HighlightRule> = emptyList())
    data class ImportResult(val added: Int, val duplicates: Int, val warnings: List<String>)
    private val gson = Gson()
    private var cached: List<HighlightRule>? = null
    private var generation = 0L
    private val index get() = File(directory, "rules.json")

    @Synchronized
    fun all(): List<HighlightRule> {
        cached?.let { return it }
        val backup = File(directory, "rules.json.bak")
        val file = index.takeIf { it.isFile } ?: backup.takeIf { it.isFile }
        val rules = if (file == null) emptyList() else file.inputStream().use { input ->
            val json = HighlightPackageParser.readLimited(input, 8 * 1024 * 1024).toString(Charsets.UTF_8)
            val data = requireNotNull(gson.fromJson(json, Index::class.java)) { "高亮规则索引为空" }
            require(data.version == 1) { "高亮规则索引格式不支持" }
            validate(data.rules).sortedBy { it.sortOrder }
        }
        cached = rules
        return rules
    }

    @Synchronized
    fun revision(): String {
        all()
        return index.lastModified().toString() + ":" + generation
    }

    @Synchronized
    fun active(bookUrl: String, styled: Boolean): List<HighlightRule> =
        all().filter { it.appliesTo(bookUrl, styled) }

    @Synchronized
    fun importPackage(pack: HighlightPackage): ImportResult {
        validate(pack.rules)
        val current = all().toMutableList()
        val identities = current.map(::identity).toHashSet()
        var duplicates = 0
        var added = 0
        for (rule in pack.rules) {
            if (!identities.add(identity(rule))) {
                duplicates++
                continue
            }
            require(current.size < HighlightPackageParser.MAX_RULES) { "高亮规则数量超过上限" }
            current.add(rule.copy(sortOrder = current.size))
            added++
        }
        // Reject invalid metadata before importing any accompanying files.
        encodedIndex(current)
        if (pack.resources.isNotEmpty()) requireNotNull(sharedAssets) { "无法保存此规则附带的阅读素材" }.importAll(pack.resources)
        var assetsChanged = false
        pack.rules.mapNotNull { it.asset }.distinct().forEach { id ->
            pack.assets[id]?.let { if (saveAsset(id, it)) assetsChanged = true }
        }
        if (added > 0) save(current)
        else if (assetsChanged) generation++
        return ImportResult(added, duplicates, pack.warnings)
    }

    @Synchronized
    fun put(rule: HighlightRule) {
        validate(listOf(rule))
        val rules = all().toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index < 0) {
            require(rules.size < HighlightPackageParser.MAX_RULES)
            rules.add(rule.copy(sortOrder = rules.size))
        } else rules[index] = rule.copy(sortOrder = rules[index].sortOrder)
        save(rules)
    }

    /** Apply a narrow edit to the latest rule without resurrecting a deleted entry. */
    @Synchronized
    fun update(id: String, change: (HighlightRule) -> HighlightRule) {
        val current = all().find { it.id == id } ?: error("此高亮规则已被删除，请返回管理页刷新")
        val changed = change(current)
        require(changed.id == id) { "不能修改高亮规则标识" }
        if (changed != current) put(changed)
    }

    @Synchronized
    fun delete(id: String) = save(all().filterNot { it.id == id })

    @Synchronized
    fun reorder(ids: List<String>) {
        val rules = all()
        val byId = rules.associateBy { it.id }
        val requested = ids.distinct().mapNotNull(byId::get)
        if (requested.size < 2) return
        val selected = requested.map { it.id }.toHashSet()
        val order = requested.iterator()
        // A search result is a subset of the index. Reorder only its occupied
        // slots, retaining hidden rules and entries added by another screen.
        val reordered = rules.map { if (it.id in selected) order.next() else it }.mapIndexed { index, rule ->
            rule.copy(sortOrder = index)
        }
        if (reordered != rules) save(reordered)
    }

    @Synchronized
    fun invalidate() {
        cached = null
        generation++
    }

    fun assetFile(id: String): File? = if (Regex("[a-f0-9]{64}").matches(id)) {
        File(File(directory, "assets"), id).takeIf { it.isFile && it.length() <= HighlightPackageParser.MAX_IMAGE_BYTES }
    } else null

    @Synchronized
    fun export(rules: List<HighlightRule> = all()): ByteArray =
        HighlightPackageParser.export(rules, resources = { id -> sharedAssets?.let { it.find(id)?.let { _ -> it.payload(id) } } }) { id ->
            assetFile(id)?.readBytes() ?: sharedAssets?.let { it.find(id)?.let { _ -> it.verifiedBytes(id) } }
        }

    @Synchronized
    fun backupTo(target: File) {
        val rules = all()
        target.mkdirs()
        File(target, "rules.json").writeText(gson.toJson(Index(rules = rules)), Charsets.UTF_8)
        rules.mapNotNull { it.asset }.distinct().forEach { id ->
            assetFile(id)?.let { source ->
                val output = File(File(target, "assets"), id)
                output.parentFile?.mkdirs()
                source.copyTo(output, overwrite = true)
            }
        }
    }

    @Synchronized
    fun restoreFrom(source: File) {
        if (!File(source, "rules.json").isFile) return
        val restored = HighlightRuleStore(source)
        val rules = restored.all()
        rules.mapNotNull { it.asset }.distinct().forEach { id ->
            restored.assetFile(id)?.let { saveAsset(id, it.readBytes()) }
        }
        save(rules)
    }

    private fun identity(rule: HighlightRule): String = gson.toJson(rule.copy(
        id = "", enabled = true, sortOrder = 0, importWarning = "", sourceBookId = null
    ))

    private fun saveAsset(id: String, bytes: ByteArray): Boolean {
        require(bytes.size <= HighlightPackageParser.MAX_IMAGE_BYTES) { "高亮背景图片过大" }
        require(HighlightPackageParser.sha256(bytes) == id) { "高亮背景图片校验失败" }
        assetFile(id)?.let { if (HighlightPackageParser.sha256(it.readBytes()) == id) return false }
        val file = File(File(directory, "assets"), id)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, id + ".tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        // Only an absent or hash-mismatched file reaches this replacement.
        check(!file.exists() || file.delete()) { "无法修复高亮背景图片" }
        check(temp.renameTo(file)) { "无法保存高亮背景图片" }
        return true
    }

    /** Gson can set Kotlin non-null fields to null; check before caching or replacing an index. */
    private fun validate(value: List<HighlightRule?>?): List<HighlightRule> {
        val rules = requireNotNull(value) { "高亮规则列表不能为空" }
        require(rules.size <= HighlightPackageParser.MAX_RULES) { "高亮规则数量超过上限" }
        val ids = hashSetOf<String>()
        fun text(value: String?, limit: Int): String {
            require(value != null && value.length <= limit) { "高亮规则包含空字段或过长内容" }
            return value
        }
        return rules.map { entry ->
            val rule = requireNotNull(entry) { "高亮规则条目不能为空" }
            val id = text(rule.id, 512)
            require(id.isNotBlank() && ids.add(id)) { "高亮规则标识为空或重复" }
            text(rule.name, 512)
            text(rule.keyword, 16384)
            text(rule.groupName, 512)
            text(rule.styleType, 512)
            text(rule.styleColorType, 512)
            text(rule.styleMode, 512)
            text(rule.styleCssText, 32768)
            text(rule.importWarning, 32768)
            require(rule.asset == null || ReaderAssetReferences.validId(rule.asset)) { "高亮背景图片标识无效" }
            require(rule.imageWidth in 0..16384 && rule.imageHeight in 0..16384 &&
                rule.imageWidth.toLong() * rule.imageHeight <= 32_000_000) { "高亮背景图片尺寸无效" }
            rule
        }
    }

    private fun encodedIndex(rules: List<HighlightRule>): ByteArray {
        validate(rules)
        val bytes = gson.toJson(Index(rules = rules)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024 * 1024) { "高亮规则索引过大" }
        return bytes
    }

    private fun save(rules: List<HighlightRule>) {
        val bytes = encodedIndex(rules)
        directory.mkdirs()
        val temp = File(directory, "rules.json.tmp")
        val backup = File(directory, "rules.json.bak")
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        if (index.exists()) {
            check(!backup.exists() || backup.delete()) { "无法替换高亮规则备份" }
            check(index.renameTo(backup)) { "无法备份高亮规则" }
        }
        if (!temp.renameTo(index)) {
            backup.renameTo(index)
            error("无法保存高亮规则")
        }
        cached = rules.toList()
        generation++
        backup.delete()
    }
}
