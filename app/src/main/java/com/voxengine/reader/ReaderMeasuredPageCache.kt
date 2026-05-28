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
