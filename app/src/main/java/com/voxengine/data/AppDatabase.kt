package com.voxengine.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VoiceEntity::class, SynthesisHistoryEntity::class, ReaderBookEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voiceDao(): VoiceDao
    abstract fun synthesisHistoryDao(): SynthesisHistoryDao
    abstract fun readerBookDao(): ReaderBookDao

    companion object {
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voxengine_tts_db"
                )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
