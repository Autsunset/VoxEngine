package com.voxengine.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderBookDao {
    @Query("SELECT * FROM reader_books ORDER BY lastUpdated DESC")
    fun getAll(): Flow<List<ReaderBookEntity>>

    @Query("SELECT * FROM reader_books WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): ReaderBookEntity?

    @Upsert
    suspend fun upsert(book: ReaderBookEntity)

    @Query(
        """
        UPDATE reader_books
        SET lastChapterIndex = :chapterIndex,
            lastPageIndex = :pageIndex,
            lastParagraphIndex = :paragraphIndex,
            lastUpdated = :timestamp
        WHERE uri = :uri
        """
    )
    suspend fun updateProgress(
        uri: String,
        chapterIndex: Int,
        pageIndex: Int,
        paragraphIndex: Int,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM reader_books WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
