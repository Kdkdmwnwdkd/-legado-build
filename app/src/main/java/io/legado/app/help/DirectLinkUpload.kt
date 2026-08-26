package io.legado.app.help

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AtomicTextFileStore
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFileReplace
import io.legado.app.utils.externalCache
import kotlinx.coroutines.currentCoroutineContext
import splitties.init.appCtx
import java.io.File

@Suppress("MemberVisibilityCanBePrivate")
object DirectLinkUpload {

    const val ruleFileName = "directLinkUploadRule.json"
    const val MAX_RULE_JSON_BYTES = DirectLinkUploadRuleCodec.MAX_JSON_BYTES

    private val configLock = Any()
    private val persistentCache by lazy { ACache.get(cacheDir = false) }

    @Throws(NoStackTraceException::class)
    suspend fun upLoad(
        fileName: String,
        file: Any,
        contentType: String,
        rule: Rule = getRule()
    ): String {
        val url = safeString(rule.uploadUrl)
        if (url.isBlank()) {
            throw NoStackTraceException("上传url未配置")
        }
        val downloadUrlRule = safeString(rule.downloadUrlRule)
        if (downloadUrlRule.isBlank()) {
            throw NoStackTraceException("下载地址规则未配置")
        }
        val safeRule = DirectLinkUploadRuleCodec.canonicalize(rule).getOrElse {
            throw NoStackTraceException(
                "直链上传规则无效：${it.localizedMessage ?: "格式不正确"}"
            )
        }
        val safeUploadUrl = safeRule.uploadUrl
        val safeDownloadUrlRule = safeRule.downloadUrlRule
        var mFileName = fileName
        var mFile = file
        var mContentType = contentType
        if (safeRule.compress && contentType != "application/zip") {
            mFileName = "$fileName.zip"
            mContentType = "application/zip"
            mFile = when (file) {
                is File -> {
                    val zipFile = File(FileUtils.getPath(appCtx.externalCache, "upload", mFileName))
                    zipFile.createFileReplace()
                    ZipUtils.zipFile(file, zipFile)
                    zipFile
                }

                is ByteArray -> ZipUtils.zipByteArray(file, fileName)
                is String -> ZipUtils.zipByteArray(file.toByteArray(), fileName)
                else -> ZipUtils.zipByteArray(GSON.toJson(file).toByteArray(), fileName)
            }
        }
        val analyzeUrl = AnalyzeUrl(safeUploadUrl)
        val res = analyzeUrl.upload(mFileName, mFile, mContentType)
        if (mFile is File) {
            mFile.delete()
        }
        val analyzeRule = AnalyzeRule().setContent(res.body, res.url)
            .setCoroutineContext(currentCoroutineContext())
        val downloadUrl = analyzeRule.getString(safeDownloadUrlRule)
        if (downloadUrl.isBlank()) {
            throw NoStackTraceException("上传失败,${res.body}")
        }
        return downloadUrl
    }

    val defaultRules: List<Rule> by lazy {
        runCatching {
            val json = appCtx.assets
                .open("defaultData${File.separator}directLinkUpload.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            DirectLinkUploadRuleCodec.decodeList(json).getOrThrow()
        }.onFailure {
            AppLog.put("读取默认直链上传规则失败\n${it.localizedMessage}", it)
        }.getOrDefault(emptyList())
    }

    fun getRule(): Rule {
        return getConfig() ?: defaultRules.firstOrNull()?.copy() ?: Rule("", "", "")
    }

    fun getConfig(): Rule? = synchronized(configLock) {
        val target = configStorageFile() ?: return@synchronized null
        val store = AtomicTextFileStore(target)
        runCatching {
            store.recoverInterruptedCommit()
        }.onFailure {
            AppLog.put("恢复直链上传规则存储状态失败", it)
        }.getOrElse { return@synchronized null }
        if (!target.isFile) return@synchronized null
        if (target.length() > MAX_RULE_JSON_BYTES.toLong()) {
            AppLog.put("已丢弃过大的直链上传规则缓存: ${target.length()} bytes")
            runCatching { deleteConfigStorageUnlocked(target) }
                .onFailure { AppLog.put("删除过大的直链上传规则缓存失败", it) }
            return@synchronized null
        }
        val json = runCatching { target.readText(Charsets.UTF_8) }
            .onFailure { AppLog.put("读取直链上传规则失败", it) }
            .getOrElse { return@synchronized null }
        DirectLinkUploadRuleCodec.decode(json).fold(
            onSuccess = { it },
            onFailure = {
                AppLog.put("已丢弃无效的直链上传规则缓存", it)
                runCatching { deleteConfigStorageUnlocked(target) }
                    .onFailure { error ->
                        AppLog.put("删除无效的直链上传规则缓存失败", error)
                    }
                null
            }
        )
    }

    internal fun decodeRule(json: String?): Result<Rule> {
        return DirectLinkUploadRuleCodec.decode(json)
    }

    internal fun encodeRule(rule: Rule): Result<String> {
        return DirectLinkUploadRuleCodec.encode(rule)
    }

    internal fun saveRule(rule: Rule): Result<Unit> {
        return encodeRule(rule).mapCatching { json ->
            synchronized(configLock) {
                val target = configStorageFile()
                    ?: error("直链上传规则存储不可用")
                AtomicTextFileStore(target).writeVerified(json) { storedJson ->
                    DirectLinkUploadRuleCodec.decode(storedJson).isSuccess
                }
            }
        }
    }

    internal fun importRule(json: String): Result<Rule> {
        return decodeRule(json).mapCatching { rule ->
            saveRule(rule).getOrThrow()
            rule
        }
    }

    internal fun configStorageFile(): File? = persistentCache.peekFile(ruleFileName)

    internal fun isConfigStorageFile(file: File): Boolean {
        val target = configStorageFile() ?: return false
        return runCatching { target.canonicalFile == file.canonicalFile }
            .getOrDefault(target.absoluteFile == file.absoluteFile)
    }

    internal fun snapshotConfigTo(snapshot: File): Result<Boolean> = runCatching {
        synchronized(configLock) {
            val target = configStorageFile()
                ?: error("直链上传规则存储不可用")
            AtomicTextFileStore(target).recoverInterruptedCommit()
            if (!target.isFile) return@synchronized false
            if (target.length() > MAX_RULE_JSON_BYTES.toLong()) {
                AppLog.put("快照前已丢弃过大的直链上传规则缓存: ${target.length()} bytes")
                deleteConfigStorageUnlocked(target)
                return@synchronized false
            }
            val json = target.readText(Charsets.UTF_8)
            val rule = DirectLinkUploadRuleCodec.decode(json).getOrElse { error ->
                AppLog.put("快照前已丢弃无效的直链上传规则缓存", error)
                deleteConfigStorageUnlocked(target)
                return@synchronized false
            }
            val canonicalJson = DirectLinkUploadRuleCodec.encode(rule).getOrThrow()
            snapshot.parentFile?.let { parent ->
                check(parent.exists() || parent.mkdirs()) {
                    "无法创建直链上传规则快照目录: ${parent.absolutePath}"
                }
            }
            snapshot.writeText(canonicalJson, Charsets.UTF_8)
            true
        }
    }

    internal fun restoreConfigSnapshot(snapshot: File?, existed: Boolean): Result<Unit> =
        runCatching {
            synchronized(configLock) {
                val target = configStorageFile()
                    ?: error("直链上传规则存储不可用")
                if (!existed) {
                    deleteConfigStorageUnlocked(target)
                    return@synchronized
                }
                val source = requireNotNull(snapshot) { "直链上传规则快照缺失" }
                require(source.isFile) { "直链上传规则快照不存在" }
                require(source.length() <= MAX_RULE_JSON_BYTES.toLong()) {
                    "直链上传规则快照超过大小限制"
                }
                val json = source.readText(Charsets.UTF_8)
                val canonicalJson = DirectLinkUploadRuleCodec.decode(json)
                    .mapCatching { DirectLinkUploadRuleCodec.encode(it).getOrThrow() }
                    .getOrThrow()
                AtomicTextFileStore(target).writeVerified(canonicalJson) { storedJson ->
                    DirectLinkUploadRuleCodec.decode(storedJson).isSuccess
                }
            }
        }

    fun putConfig(rule: Rule): Result<Unit> = saveRule(rule)

    fun delConfig(): Result<Unit> = runCatching {
        synchronized(configLock) {
            configStorageFile()?.let(::deleteConfigStorageUnlocked)
        }
    }

    fun getSummary(): String {
        return safeString(getRule().summary)
    }

    fun getExpiryDate(): Int {
        return getRule().expiryDate
    }

    private fun safeString(value: String?): String = value.orEmpty()

    private fun deleteConfigStorageUnlocked(target: File) {
        val removedSize = AtomicTextFileStore(target).delete()
        persistentCache.forgetFile(ruleFileName, removedSize)
    }

    @Keep
    data class Rule(
        var uploadUrl: String, //创建分享链接
        var downloadUrlRule: String, //下载链接规则
        var summary: String, //注释
        var compress: Boolean = false, //是否压缩
        var expiryDate: Int = 0, //有效期天数，0 表示永久
    ) {

        override fun toString(): String {
            return safeString(summary)
        }

    }

}
