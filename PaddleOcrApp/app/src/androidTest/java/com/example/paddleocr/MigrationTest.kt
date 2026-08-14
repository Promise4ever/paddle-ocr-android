package com.example.paddleocr

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.paddleocr.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证 v1 -> v2 迁移：历史数据完整保留，且 history(time) 索引确实建立。
 * 运行方式：连接设备/模拟器后执行 ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList()
    )

    @Test
    fun migrate1To2_keepsHistoryDataAndCreatesIndex() {
        // 用导出的 v1 schema 创建一个含数据的旧版数据库
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO history " +
                    "(time, linesJson, markdown, modelName, sourceImagePath, thumbnailPath, favorite) " +
                    "VALUES (1700000000000, '[]', NULL, NULL, NULL, NULL, 0)"
            )
            close()
        }

        // 执行迁移并校验迁移后的库结构与 Room 期望的 v2 schema 一致
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)

        // 数据保留
        db.query("SELECT time FROM history").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1700000000000L, cursor.getLong(0))
        }

        // 索引已建立
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_history_time'"
        ).use { cursor ->
            assertEquals(1, cursor.count)
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
