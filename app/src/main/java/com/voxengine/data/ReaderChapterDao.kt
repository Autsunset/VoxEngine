package com.voxengine.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReaderChapterDao {
    @Query("SELECT * FROM reader_chapters WHERE bookUri = :bookUri ORDER BY chapterIndex ASC")
    suspend fun getChapters(bookUri: String): List<ReaderChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ReaderChapterEntity>)

    @Query("DELETE FROM reader_chapters WHERE bookUri = :bookUri")
    suspend fun deleteByBookUri(bookUri: String)
}
