package com.voxengine.reader

import java.util.concurrent.ConcurrentHashMap

/**
 * 视口测量分页缓存。按书 LRU 上限 [MAX_BOOKS]，避免长时间听多本书时无限涨内存。
 */
object ReaderMeasuredPageCache {
    private const val MAX_BOOKS = 3
    private val pagesByBook = ConcurrentHashMap<String, ConcurrentHashMap<Int, List<TxtPage>>>()
    // accessOrder LinkedHashMap 仅作书级 LRU 顺序；实际数据在 ConcurrentHashMap。
    private val bookOrder = object : LinkedHashMap<String, Boolean>(MAX_BOOKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            if (size <= MAX_BOOKS) return false
            val key = eldest?.key
            if (key != null) pagesByBook.remove(key)
            return true
        }
    }

    @Synchronized
    fun putChapterPages(uri: String, chapterIndex: Int, pages: List<TxtPage>) {
        if (uri.isBlank() || pages.isEmpty()) return
        pagesByBook.getOrPut(uri) { ConcurrentHashMap() }[chapterIndex] = pages
        bookOrder[uri] = true
    }

    @Synchronized
    fun getChapterPages(uri: String, chapterIndex: Int): List<TxtPage>? {
        val pages = pagesByBook[uri]?.get(chapterIndex) ?: return null
        bookOrder[uri] = true
        return pages
    }

    @Synchronized
    fun clearBook(uri: String) {
        pagesByBook.remove(uri)
        bookOrder.remove(uri)
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
