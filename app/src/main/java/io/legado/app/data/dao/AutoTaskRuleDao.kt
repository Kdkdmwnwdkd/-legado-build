package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.model.AutoTaskRule
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoTaskRuleDao {

    @Query("SELECT * FROM auto_task_rules ORDER BY sortOrder ASC, id ASC")
    fun flowAll(): Flow<List<AutoTaskRule>>

    @Query("SELECT * FROM auto_task_rules ORDER BY sortOrder ASC, id ASC")
    fun all(): List<AutoTaskRule>

    @Query("SELECT * FROM auto_task_rules WHERE id = :id LIMIT 1")
    fun get(id: String): AutoTaskRule?

    @Query("SELECT COUNT(*) FROM auto_task_rules")
    fun count(): Int

    @Query("SELECT MAX(sortOrder) FROM auto_task_rules")
    fun maxOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg rules: AutoTaskRule)

    @Update
    fun update(rule: AutoTaskRule): Int

    @Query("UPDATE auto_task_rules SET sortOrder = :sortOrder WHERE id = :id")
    fun updateOrder(id: String, sortOrder: Int): Int

    @Query("UPDATE auto_task_rules SET enable = :enabled WHERE id = :id")
    fun updateEnabled(id: String, enabled: Boolean): Int

    @Query("DELETE FROM auto_task_rules WHERE id = :id")
    fun delete(id: String): Int

    @Query("DELETE FROM auto_task_rules")
    fun deleteAll(): Int
}
