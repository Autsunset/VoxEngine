package com.voxengine.reader

object NovelParser {
    fun parse(bytes: ByteArray, displayName: String? = null): List<TxtChapter> {
        val isEpub = displayName?.endsWith(".epub", ignoreCase = true) == true || EpubNovelParser.isEpub(bytes)
        return if (isEpub) EpubNovelParser.parse(bytes) else TxtNovelParser.parse(TxtNovelParser.decode(bytes))
    }
}
