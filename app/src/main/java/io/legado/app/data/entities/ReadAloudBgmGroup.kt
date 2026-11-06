package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.R
import splitties.init.appCtx

@Entity(
    tableName = "read_aloud_bgm_groups",
    indices = [
        Index(value = ["sortOrder", "id"]),
        Index(value = ["assetType", "sortOrder", "id"])
    ]
)
data class ReadAloudBgmGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val name: String = "",
    @ColumnInfo(defaultValue = "'bgm'")
    val assetType: String = ReadAloudBgmTrack.TYPE_BGM,
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun displayName(): String = if (name.isBlank() || name == DEFAULT_NAME) {
        appCtx.getString(R.string.default_group)
    } else {
        name
    }

    fun isDefaultGroup(): Boolean = id == 0L || name == DEFAULT_NAME

    companion object {
        /** 数据库内默认分组的固定存储值，不随界面语言变化。 */
        const val DEFAULT_NAME = "默认分组"
    }
}
