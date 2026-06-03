package com.voxengine.reader

import java.util.concurrent.ConcurrentHashMap

object ReaderMeasuredPageCache {
    private val pagesByBook = ConcurrentHashMap<String, ConcurrentHashMap<Int, List<TxtPage>>>()

    fun putChapterPages(uri: String, chapterIndex: Int, pages: List<TxtPage>) {
        if (uri.isBlank() || pages.isEmpty()) return
        pagesByBook.getOrPut(uri) { ConcurrentHashMap() }[chapterIndex] = pages
    }

    fun getChapterPages(uri: String, chapterIndex: Int): List<TxtPage>? =
        pagesByBook[uri]?.get(chapterIndex)

    fun clearBook(uri: String) {
        pagesByBook.remove(uri)
    }
}

object ReaderChapterCache {
    private const val MAX_BOOKS = 3
    private val chaptersByBook = object : LinkedHashMap<String, List<TxtChapter>>(MAX_BOOKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TxtChapter>>?): Boolean =
            size > MAX_BOOKS
    }

    @Synchronized
    fun putChapters(uri: String, chapters: List<TxtChapter>) {
        if (uri.isBlank() || chapters.isEmpty()) return
        chaptersByBook[uri] = chapters
    }

    @Synchronized
    fun getChapters(uri: String): List<TxtChapter>? = chaptersByBook[uri]

    @Synchronized
    fun clearBook(uri: String) {
        chaptersByBook.remove(uri)
    }
}
