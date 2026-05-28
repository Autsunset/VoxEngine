package com.voxengine.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reader_books")
data class ReaderBookEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val lastChapterIndex: Int = 0,
    val lastPageIndex: Int = 0,
    val lastParagraphIndex: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
