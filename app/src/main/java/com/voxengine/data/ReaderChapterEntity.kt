package com.voxengine.data

import androidx.room.Entity
import androidx.room.Index
import com.voxengine.reader.TxtChapter

@Entity(
    tableName = "reader_chapters",
    primaryKeys = ["bookUri", "chapterIndex"],
    indices = [Index("bookUri")]
)
data class ReaderChapterEntity(
    val bookUri: String,
    val chapterIndex: Int,
    val title: String,
    val content: String,
    val isVolume: Boolean = false
) {
    fun toTxtChapter(): TxtChapter = TxtChapter(
        title = title,
        content = content,
        isVolume = isVolume
    )

    companion object {
        fun fromTxtChapter(bookUri: String, chapterIndex: Int, chapter: TxtChapter): ReaderChapterEntity =
            ReaderChapterEntity(
                bookUri = bookUri,
                chapterIndex = chapterIndex,
                title = chapter.title,
                content = chapter.content,
                isVolume = chapter.isVolume
            )
    }
}
