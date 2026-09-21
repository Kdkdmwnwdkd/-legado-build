package io.legado.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoTaskMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration112To113CreatesUsableRuleTable() {
        helper.createDatabase(TEST_DB, 112).close()
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            113,
            true,
            DatabaseMigrations.MIGRATION_112_113
        )
        db.execSQL(
            """
            INSERT INTO auto_task_rules (id, name, cron, script)
            VALUES ('test', 'Test', '*/30 * * * *', '1')
            """.trimIndent()
        )
        db.query("SELECT enable, enabledCookieJar, lastRunAt, sortOrder FROM auto_task_rules")
            .use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
            }
        db.close()
    }

    companion object {
        private const val TEST_DB = "autotask-migration-test"
    }
}
