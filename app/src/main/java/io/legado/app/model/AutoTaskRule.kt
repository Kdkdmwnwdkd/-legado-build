package io.legado.app.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * A user-owned scheduled script.
 *
 * The field names intentionally stay compatible with the legacy AutoTask
 * export format. Persistence and execution are layered on top of this data
 * object, so it can be validated and scheduled in JVM tests independently of
 * an Android service.
 */
@Entity(tableName = "auto_task_rules")
data class AutoTaskRule(
    @PrimaryKey
    @SerializedName("id")
    var id: String = UUID.randomUUID().toString(),
    @SerializedName("name")
    var name: String = "",
    @ColumnInfo(defaultValue = "1")
    @SerializedName("enable")
    var enable: Boolean = true,
    @SerializedName("cron")
    var cron: String? = DEFAULT_CRON,
    @SerializedName("loginUrl")
    var loginUrl: String? = null,
    @SerializedName("loginUi")
    var loginUi: String? = null,
    @SerializedName("loginCheckJs")
    var loginCheckJs: String? = null,
    @SerializedName("comment")
    var comment: String? = null,
    @SerializedName("script")
    var script: String = "",
    @SerializedName("header")
    var header: String? = null,
    @SerializedName("jsLib")
    var jsLib: String? = null,
    @SerializedName("concurrentRate")
    var concurrentRate: String? = null,
    @ColumnInfo(defaultValue = "1")
    @SerializedName("enabledCookieJar")
    var enabledCookieJar: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    @SerializedName("lastRunAt")
    var lastRunAt: Long = 0L,
    @SerializedName("lastResult")
    var lastResult: String? = null,
    @SerializedName("lastError")
    var lastError: String? = null,
    @SerializedName("lastLog")
    var lastLog: String? = null,
    @ColumnInfo(defaultValue = "0")
    @SerializedName("sortOrder")
    var sortOrder: Int = 0
) {

    companion object {
        const val DEFAULT_CRON = "*/30 * * * *"

        /** Removes wrappers accepted by old exports before script execution. */
        fun normalizeScript(script: String): String {
            val trimmed = script.trim()
            return when {
                trimmed.startsWith("@js:", ignoreCase = true) -> trimmed.substring(4).trim()
                trimmed.startsWith("<js>", ignoreCase = true) &&
                    trimmed.contains("</", ignoreCase = true) ->
                    trimmed.substring(4, trimmed.lastIndexOf('<')).trim()
                else -> trimmed
            }
        }
    }

    fun normalizedScript(): String = normalizeScript(script)
}
