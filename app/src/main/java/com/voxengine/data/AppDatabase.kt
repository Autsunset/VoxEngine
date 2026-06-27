package com.voxengine.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VoiceEntity::class, SynthesisHistoryEntity::class, ReaderBookEntity::class, ReaderChapterEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voiceDao(): VoiceDao
    abstract fun synthesisHistoryDao(): SynthesisHistoryDao
    abstract fun readerBookDao(): ReaderBookDao
    abstract fun readerChapterDao(): ReaderChapterDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "voices", "audioBase64", "TEXT")
                addColumnIfMissing(db, "voices", "engineId", "TEXT NOT NULL DEFAULT 'mimo'")
                addColumnIfMissing(db, "voices", "createdAt", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "synthesis_history", "speed", "REAL NOT NULL DEFAULT 1.0")
                addColumnIfMissing(db, "synthesis_history", "engineId", "TEXT NOT NULL DEFAULT 'mimo'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reader_books` (
                        `uri` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `lastChapterIndex` INTEGER NOT NULL,
                        `lastPageIndex` INTEGER NOT NULL,
                        `lastParagraphIndex` INTEGER NOT NULL,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`uri`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reader_chapters` (
                        `bookUri` TEXT NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `isVolume` INTEGER NOT NULL,
                        PRIMARY KEY(`bookUri`, `chapterIndex`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reader_chapters_bookUri` ON `reader_chapters` (`bookUri`)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 音色元数据：性别 / 年龄段 / 标签 / 分组，均可空。用于分组管理与分角色路由。
                addColumnIfMissing(db, "voices", "gender", "TEXT")
                addColumnIfMissing(db, "voices", "ageGroup", "TEXT")
                addColumnIfMissing(db, "voices", "tags", "TEXT")
                addColumnIfMissing(db, "voices", "groupId", "TEXT")
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            definition: String
        ) {
            val exists = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) {
                db.execSQL("ALTER TABLE `$tableName` ADD COLUMN `$columnName` $definition")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voxengine_tts_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
