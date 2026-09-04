package com.example.paddleocr.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.paddleocr.App

@Entity(
    tableName = "history",
    indices = [
        Index(value = ["time"]),
        Index(value = ["favorite", "favoritedAt"])
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val linesJson: String,
    val markdown: String? = null,
    val modelName: String? = null,
    val sourceImagePath: String? = null,
    val thumbnailPath: String? = null,
    val favorite: Boolean = false,
    /** 收藏发生时间；取消收藏时为 null，用于收藏分类独立排序。 */
    val favoritedAt: Long? = null
)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(e: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY time DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<HistoryEntity>

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int

    @Query("SELECT * FROM history WHERE favorite = 1 ORDER BY favoritedAt DESC, time DESC LIMIT :limit OFFSET :offset")
    suspend fun favoritePage(limit: Int, offset: Int): List<HistoryEntity>

    @Query("SELECT COUNT(*) FROM history WHERE favorite = 1")
    suspend fun favoriteCount(): Int

    @Query(
        "SELECT * FROM history WHERE (linesJson LIKE '%' || :q || '%' " +
            "OR COALESCE(markdown, '') LIKE '%' || :q || '%') " +
            "ORDER BY time DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun search(q: String, limit: Int, offset: Int): List<HistoryEntity>

    @Query(
        "SELECT COUNT(*) FROM history WHERE (linesJson LIKE '%' || :q || '%' " +
            "OR COALESCE(markdown, '') LIKE '%' || :q || '%')"
    )
    suspend fun searchCount(q: String): Int

    @Query(
        "SELECT * FROM history WHERE favorite = 1 AND (linesJson LIKE '%' || :q || '%' " +
            "OR COALESCE(markdown, '') LIKE '%' || :q || '%') " +
            "ORDER BY favoritedAt DESC, time DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun searchFavorites(q: String, limit: Int, offset: Int): List<HistoryEntity>

    @Query(
        "SELECT COUNT(*) FROM history WHERE favorite = 1 AND (linesJson LIKE '%' || :q || '%' " +
            "OR COALESCE(markdown, '') LIKE '%' || :q || '%')"
    )
    suspend fun searchFavoriteCount(q: String): Int

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun byId(id: Long): HistoryEntity?

    @Query("SELECT * FROM history ORDER BY time DESC LIMIT 1")
    suspend fun latest(): HistoryEntity?

    @Query("UPDATE history SET favorite = :fav, favoritedAt = :favoritedAt WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean, favoritedAt: Long?)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM history WHERE favorite = 0")
    suspend fun unfavoritedEntries(): List<HistoryEntity>

    @Query("DELETE FROM history WHERE favorite = 0")
    suspend fun deleteUnfavorited()
}

@Database(entities = [HistoryEntity::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 -> v2：为 history.time 增加索引，提升历史列表/搜索按时间倒序查询的性能。
         * 后续任何 schema 变更都必须在这里补一条 Migration，禁止再回退到清库策略。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_time ON history(time)")
            }
        }

        /** v2 -> v3：新增收藏时间，并让旧收藏以原识别时间作为兼容排序时间。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN favoritedAt INTEGER")
                db.execSQL("UPDATE history SET favoritedAt = time WHERE favorite = 1")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_history_favorite_favoritedAt " +
                        "ON history(favorite, favoritedAt)"
                )
            }
        }

        fun get(): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(App.context, AppDatabase::class.java, "paddle_ocr.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
