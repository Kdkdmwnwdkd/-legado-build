package io.legado.app.model

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.CacheManager
import io.legado.app.service.AutoTaskService
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Transactional persistence facade. Execution and Android scheduling are separate layers. */
object AutoTask {
    const val SOURCE_KEY = "auto_task"
    const val SOURCE_TYPE = "autoTask"
    /** Explicit extras used when opening the source-login screen for a task. */
    const val EXTRA_TASK_ID = "autoTaskId"
    const val EXTRA_SOURCE_KEY = "autoTaskSourceKey"
    const val DEFAULT_CRON = AutoTaskRule.DEFAULT_CRON
    private const val BOOK_TASK_PREFIX = "book_update:"
    private const val MAX_LOG_LENGTH = 4_000
    private val sharedExecutionLock = Mutex()

    /** Stable ID shared by book details, bookshelf batch actions and deletion. */
    fun bookTaskId(bookUrl: String): String =
        BOOK_TASK_PREFIX + MD5Utils.md5Encode16(bookUrl)

    /**
     * Builds the small JS value consumed by [AutoTaskProtocol]. Keeping the
     * book URL in the action (rather than in the task id alone) makes exported
     * rules portable and lets the protocol validate the target explicitly.
     */
    fun buildBookUpdateScript(
        bookUrl: String,
        notifyEnabled: Boolean = true,
        cacheEnabled: Boolean = false
    ): String {
        require(bookUrl.isNotBlank()) { "bookUrl must not be blank" }
        val action = linkedMapOf<String, Any?>(
            "type" to "refreshToc",
            "bookUrl" to bookUrl,
            "notify" to linkedMapOf<String, Any?>(
                "enable" to notifyEnabled,
                "minCount" to 1
            ),
            "cache" to linkedMapOf<String, Any?>("enable" to cacheEnabled)
        )
        val payload = linkedMapOf<String, Any?>("actions" to listOf(action))
        return "var __autoTask = ${GSON.toJson(payload)};\n__autoTask"
    }

    @Synchronized
    fun all(): List<AutoTaskRule> {
        migrateLegacyRulesIfNeeded()
        return appDb.autoTaskRuleDao.all()
    }

    @Synchronized
    fun flowAll(): Flow<List<AutoTaskRule>> {
        migrateLegacyRulesIfNeeded()
        return appDb.autoTaskRuleDao.flowAll()
    }

    /**
     * Resolves a persisted task by its stable id. The lookup is also used by
     * entry points that can run before the task screen has opened, so it must
     * perform the legacy-cache migration itself rather than relying on
     * [all] or [flowAll] having been called first.
     */
    @Synchronized
    fun get(id: String): AutoTaskRule? {
        val normalizedId = id.trim().takeIf { it.isNotEmpty() } ?: return null
        migrateLegacyRulesIfNeeded()
        return appDb.autoTaskRuleDao.get(normalizedId)
            ?: appDb.autoTaskRuleDao.all().firstOrNull { it.id.trim() == normalizedId }
    }

    /**
     * Resolves the id carried by a temporary source key such as
     * `auto_task:<id>`. Prefix matching is deliberately case-insensitive so
     * links produced by older builds remain usable.
     */
    fun getBySourceKey(rawSourceKey: String?): AutoTaskRule? {
        val candidates = sourceTaskIdCandidates(rawSourceKey).toMutableList()
        normalizeSourceTaskId(rawSourceKey)?.let { normalized ->
            // Also probe canonical prefixes. A few early exports persisted the
            // prefix as part of the primary key itself.
            candidates += sourceKey(normalized)
            candidates += "AutoTask:$normalized"
            candidates += "auto-task:$normalized"
        }
        return candidates
            .distinct()
            .asSequence()
            .mapNotNull(::get)
            .firstOrNull()
    }

    fun sourceKey(taskId: String): String = "$SOURCE_KEY:${taskId.trim()}"

    fun isAutoTaskType(type: String?): Boolean {
        return type?.trim()?.equals(SOURCE_TYPE, ignoreCase = true) == true ||
            type?.trim()?.equals(SOURCE_KEY, ignoreCase = true) == true ||
            type?.trim()?.equals("auto-task", ignoreCase = true) == true
    }

    /** Returns true for a key with any of the prefixes emitted by old builds. */
    fun isAutoTaskSourceKey(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        val separator = text.indexOf(':')
        if (separator <= 0) return false
        val prefix = text.substring(0, separator)
        return prefix.equals(SOURCE_KEY, ignoreCase = true) ||
            prefix.equals(SOURCE_TYPE, ignoreCase = true) ||
            prefix.equals("auto-task", ignoreCase = true)
    }

    /**
     * Resolves the task id carried by a login Intent. The dedicated extras are
     * preferred, while the plain `key`/`taskId` names keep links from older
     * builds working. This function is pure so its compatibility contract can
     * be covered without creating an Android activity or database.
     */
    fun resolveTaskId(
        sourceType: String?,
        key: String?,
        taskId: String? = null,
        sourceKey: String? = null
    ): String? {
        val embeddedTypeId = sourceType
            ?.takeIf(::isAutoTaskSourceKey)
            ?.let(::normalizeSourceTaskId)
        val dedicatedTaskId = taskId?.trim()?.takeIf { it.isNotEmpty() }
        val dedicatedSourceKey = sourceKey?.trim()?.takeIf { it.isNotEmpty() }
        val keyValue = key?.trim()?.takeIf { it.isNotEmpty() }
        val candidate = when {
            embeddedTypeId != null -> embeddedTypeId
            isAutoTaskType(sourceType) -> dedicatedTaskId
                ?: dedicatedSourceKey?.let(::normalizeSourceTaskId)
                ?: keyValue?.let(::normalizeSourceTaskId)
            dedicatedSourceKey != null && isAutoTaskSourceKey(dedicatedSourceKey) ->
                normalizeSourceTaskId(dedicatedSourceKey)
            keyValue != null && isAutoTaskSourceKey(keyValue) ->
                normalizeSourceTaskId(keyValue)
            dedicatedTaskId != null -> dedicatedTaskId
            else -> null
        }
        return candidate?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun normalizeSourceTaskId(sourceKey: String?): String? {
        return sourceTaskIdCandidates(sourceKey).lastOrNull()
    }

    /** Includes the original key so databases from an older build remain readable. */
    fun sourceTaskIdCandidates(sourceKey: String?): List<String> {
        var value = sourceKey?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val candidates = linkedSetOf<String>()
        while (value.isNotEmpty()) {
            val separator = value.indexOf(':')
            if (separator > 0 && isAutoTaskPrefix(value.substring(0, separator))) {
                val next = value.substring(separator + 1).trim()
                if (next.isEmpty()) break
                candidates += value
                value = next
            } else {
                candidates += value
                break
            }
        }
        return candidates.toList()
    }

    private fun isAutoTaskPrefix(prefix: String): Boolean {
        return prefix.equals(SOURCE_KEY, ignoreCase = true) ||
            prefix.equals(SOURCE_TYPE, ignoreCase = true) ||
            prefix.equals("auto-task", ignoreCase = true)
    }

    /** Mutable copy kept for callers that need to build a batch operation. */
    @Synchronized
    fun getRules(): MutableList<AutoTaskRule> = all().toMutableList()

    @Synchronized
    fun upsert(rule: AutoTaskRule): AutoTaskRule {
        val errors = AutoTaskRuleValidator.validate(rule)
        require(errors.isEmpty()) { "Invalid AutoTask rule: $errors" }
        val stored = appDb.runInTransaction<AutoTaskRule> {
            val existing = appDb.autoTaskRuleDao.get(rule.id)
            val normalized = rule.copy(
                id = rule.id.trim(),
                name = rule.name.trim(),
                cron = rule.cron?.trim(),
                script = rule.normalizedScript(),
                sortOrder = existing?.sortOrder
                    ?: ((appDb.autoTaskRuleDao.maxOrder() ?: -1) + 1)
            )
            appDb.autoTaskRuleDao.insert(normalized)
            normalized
        }
        refreshSchedule()
        return stored
    }

    @Synchronized
    fun update(id: String, transform: (AutoTaskRule) -> AutoTaskRule): AutoTaskRule? {
        val existing = appDb.autoTaskRuleDao.get(id) ?: return null
        val candidate = transform(existing).copy(id = existing.id, sortOrder = existing.sortOrder)
        return upsert(candidate)
    }

    /** Persists execution metadata without re-validating the user-authored rule. */
    @Synchronized
    fun updateRuntime(id: String, transform: (AutoTaskRule) -> AutoTaskRule): AutoTaskRule? {
        val existing = appDb.autoTaskRuleDao.get(id) ?: return null
        val updated = transform(existing).copy(id = existing.id, sortOrder = existing.sortOrder)
        return updated.takeIf { appDb.autoTaskRuleDao.update(it) == 1 }
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val existing = appDb.autoTaskRuleDao.get(id) ?: return false
        if (enabled) {
            require(AutoTaskRuleValidator.isValid(existing)) { "Cannot enable an invalid AutoTask rule" }
        }
        val changed = appDb.autoTaskRuleDao.updateEnabled(id, enabled) == 1
        if (changed) refreshSchedule()
        return changed
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val changed = appDb.autoTaskRuleDao.delete(id) == 1
        if (changed) refreshSchedule()
        return changed
    }

    @Synchronized
    fun delete(vararg ids: String): Int {
        if (ids.isEmpty()) return 0
        val deleted = appDb.runInTransaction<Int> {
            ids.distinct().count { appDb.autoTaskRuleDao.delete(it) == 1 }
        }
        if (deleted > 0) refreshSchedule()
        return deleted
    }

    @Synchronized
    fun upsert(rules: List<AutoTaskRule>): List<AutoTaskRule> {
        if (rules.isEmpty()) return emptyList()
        val stored = appDb.runInTransaction<List<AutoTaskRule>> {
            rules.map { rule -> upsertInTransaction(rule) }
        }
        refreshSchedule()
        return stored
    }

    @Synchronized
    fun reorder(orderedIds: List<String>) {
        appDb.runInTransaction {
            val existing = appDb.autoTaskRuleDao.all()
            val byId = existing.associateBy(AutoTaskRule::id)
            val seen = HashSet<String>(existing.size)
            val ordered = buildList(existing.size) {
                orderedIds.forEach { id ->
                    if (seen.add(id)) byId[id]?.let(::add)
                }
                existing.forEach { rule -> if (seen.add(rule.id)) add(rule) }
            }
            ordered.forEachIndexed { index, rule ->
                appDb.autoTaskRuleDao.updateOrder(rule.id, index)
            }
        }
        refreshSchedule()
    }

    /** Builds the temporary source object used to execute one task's script. */
    fun buildSource(task: AutoTaskRule): BookSource = BookSource(
        bookSourceUrl = sourceKey(task.id),
        bookSourceName = task.name
    ).apply {
        jsLib = task.jsLib
        header = task.header
        concurrentRate = task.concurrentRate
        enabledCookieJar = task.enabledCookieJar
        loginUrl = task.loginUrl
        loginUi = task.loginUi
        loginCheckJs = task.loginCheckJs
    }

    /**
     * Creates the one execution pipeline used by both the scheduler and the
     * foreground task debugger. The shared lock prevents a manual run from
     * racing a background refresh operation.
     */
    fun executionCoordinator(context: Context = appCtx): AutoTaskExecutionCoordinator {
        return AutoTaskExecutionCoordinator(
            scriptExecutor = { rule ->
                buildSource(rule).evalJS(rule.normalizedScript())
            },
            actionHandler = { rule, value, logger ->
                AutoTaskProtocol.handle(
                    result = value,
                    context = context.applicationContext,
                    taskName = rule.name,
                    logger = logger
                ).let { handled ->
                    AutoTaskActionResult(
                        handled = handled.handled,
                        summaries = handled.details,
                        detail = handled.summary
                    )
                }
            },
            executionLock = sharedExecutionLock
        )
    }

    fun normalizeScript(script: String): String = AutoTaskRule.normalizeScript(script)

    fun start(context: Context) = AutoTaskService.start(context)

    fun stop(context: Context) = AutoTaskService.stop(context)

    fun refreshSchedule(context: Context = appCtx) {
        if (context.getPrefBoolean(PreferKey.autoTaskService)) {
            AutoTaskService.refresh(context)
        }
    }

    fun buildLastLog(
        lines: List<String>,
        detail: String?,
        costMs: Long,
        runAt: Long
    ): String = buildString {
        append("[OK] ").append(formatLogTime(runAt)).append('\n')
        append("耗时: ").append(costMs.coerceAtLeast(0L)).append("ms")
        if (lines.isNotEmpty()) {
            append('\n').append("动作:")
            lines.take(64).forEach { append('\n').append("- ").append(it) }
        }
        detail?.takeIf { it.isNotBlank() }?.let {
            append('\n').append("返回: ").append(it)
        }
    }.ifBlank { "执行完成" }.take(MAX_LOG_LENGTH)

    fun buildErrorLog(msg: String, error: Throwable?, runAt: Long): String = buildString {
        append("[FAIL] ").append(formatLogTime(runAt)).append('\n')
        append("错误: ").append(msg)
        error?.stackTraceStr?.takeIf { it.isNotBlank() }?.let {
            append('\n').append("堆栈:\n").append(it)
        }
    }.take(MAX_LOG_LENGTH)

    /**
     * Returns true only when legacy data was committed and the matching cache
     * row was removed in the same transaction.
     */
    @Synchronized
    fun migrateLegacyRulesIfNeeded(): Boolean {
        if (appDb.autoTaskRuleDao.count() != 0) return false
        val legacyJson = CacheManager.get(AutoTaskLegacyMigration.CACHE_KEY) ?: return false
        val rules = AutoTaskLegacyMigration.parse(legacyJson).getOrNull() ?: return false
        val migrated = appDb.runInTransaction<Boolean> {
            if (appDb.autoTaskRuleDao.count() != 0) return@runInTransaction false
            if (rules.isNotEmpty()) {
                appDb.autoTaskRuleDao.insert(*rules.toTypedArray())
                check(appDb.autoTaskRuleDao.count() == rules.size) {
                    "AutoTask legacy migration did not persist every rule"
                }
            }
            appDb.cacheDao.deleteIfValueMatches(AutoTaskLegacyMigration.CACHE_KEY, legacyJson)
            true
        }
        if (migrated) CacheManager.deleteMemory(AutoTaskLegacyMigration.CACHE_KEY)
        return migrated
    }

    private fun upsertInTransaction(rule: AutoTaskRule): AutoTaskRule {
        val errors = AutoTaskRuleValidator.validate(rule)
        require(errors.isEmpty()) { "Invalid AutoTask rule: $errors" }
        val existing = appDb.autoTaskRuleDao.get(rule.id)
        val stored = rule.copy(
            id = rule.id.trim(),
            name = rule.name.trim(),
            cron = rule.cron?.trim(),
            script = rule.normalizedScript(),
            sortOrder = existing?.sortOrder
                ?: ((appDb.autoTaskRuleDao.maxOrder() ?: -1) + 1)
        )
        appDb.autoTaskRuleDao.insert(stored)
        return stored
    }

    private fun formatLogTime(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
}
