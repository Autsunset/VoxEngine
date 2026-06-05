package com.voxengine.reader

object ReaderPlaybackPlanner {
    const val MIN_TTS_CHUNK_CHARS = 40
    const val MAX_TTS_CHUNK_CHARS = 180

    private const val SENTENCE_END_CHARS = "。！？；.!?;"
    private const val SOFT_SPLIT_CHARS = "，、,：:"

    data class Position(val chapterIndex: Int, val pageIndex: Int)
    data class ChunkKey(val position: Position, val paragraphIndex: Int, val chunkIndex: Int)

    fun buildPrefetchWindow(
        chapters: List<TxtChapter>,
        currentPosition: Position,
        startParagraphIndex: Int,
        nextChapterPrefetchPageCount: Int,
        pageTargetLength: Int,
        pagesForChapter: (Int) -> List<TxtPage> = { chapterIndex ->
            TxtNovelParser.paginate(chapters[chapterIndex].content, pageTargetLength)
        }
    ): List<Pair<ChunkKey, String>> = buildList {
        val currentChapterPages = pagesForChapter(currentPosition.chapterIndex)
        for (pageIndex in currentPosition.pageIndex until currentChapterPages.size) {
            addAll(
                chunkKeysForPlayback(
                    chapters = chapters,
                    position = Position(currentPosition.chapterIndex, pageIndex),
                    startParagraphIndex = if (pageIndex == currentPosition.pageIndex) startParagraphIndex else 0,
                    pageTargetLength = pageTargetLength,
                    pagesForChapter = pagesForChapter
                )
            )
        }

        val nextChapterPosition = normalizePosition(
            chapters = chapters,
            position = Position(currentPosition.chapterIndex + 1, 0),
            pageTargetLength = pageTargetLength,
            pagesForChapter = pagesForChapter
        )
        if (nextChapterPosition != null && nextChapterPosition.chapterIndex != currentPosition.chapterIndex) {
            val nextChapterPages = pagesForChapter(nextChapterPosition.chapterIndex)
            val pageCount = nextChapterPrefetchPageCount.coerceIn(0, nextChapterPages.size)
            for (pageIndex in 0 until pageCount) {
                addAll(
                    chunkKeysForPlayback(
                        chapters = chapters,
                        position = Position(nextChapterPosition.chapterIndex, pageIndex),
                        startParagraphIndex = 0,
                        pageTargetLength = pageTargetLength,
                        pagesForChapter = pagesForChapter
                    )
                )
            }
        }
    }

    fun chunkKeysForPlayback(
        chapters: List<TxtChapter>,
        position: Position,
        startParagraphIndex: Int,
        pageTargetLength: Int,
        pagesForChapter: (Int) -> List<TxtPage> = { chapterIndex ->
            TxtNovelParser.paginate(chapters[chapterIndex].content, pageTargetLength)
        }
    ): List<Pair<ChunkKey, String>> {
        val page = pagesForChapter(position.chapterIndex).getOrNull(position.pageIndex)
            ?: return emptyList()
        val startIndex = startParagraphIndex.coerceIn(0, page.paragraphs.size)
        return page.paragraphs.drop(startIndex).flatMapIndexed { offset, paragraph ->
            val paragraphIndex = startIndex + offset
            splitTextForTts(paragraph).mapIndexed { chunkIndex, chunk ->
                ChunkKey(position, paragraphIndex, chunkIndex) to chunk
            }
        }
    }

    fun splitTextForTts(text: String): List<String> {
        if (text.length <= MAX_TTS_CHUNK_CHARS) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = chooseTtsSplitEnd(text, start)
            chunks += text.substring(start, end)
            start = end
        }
        return chunks.ifEmpty { listOf(text) }.also { parts ->
            check(parts.joinToString(separator = "") == text) { "TTS split changed source text" }
        }
    }

    fun normalizePosition(
        chapters: List<TxtChapter>,
        position: Position,
        pageTargetLength: Int,
        pagesForChapter: (Int) -> List<TxtPage> = { chapterIndex ->
            TxtNovelParser.paginate(chapters[chapterIndex].content, pageTargetLength)
        }
    ): Position? {
        if (chapters.isEmpty()) return null
        var chapterIndex = position.chapterIndex.coerceIn(0, chapters.lastIndex)
        while (chapterIndex <= chapters.lastIndex) {
            val pages = pagesForChapter(chapterIndex)
            if (pages.isNotEmpty()) {
                return Position(chapterIndex, position.pageIndex.coerceIn(0, pages.lastIndex))
            }
            chapterIndex += 1
        }
        return null
    }

    fun nextPosition(
        chapters: List<TxtChapter>,
        position: Position,
        pageTargetLength: Int,
        pagesForChapter: (Int) -> List<TxtPage> = { chapterIndex ->
            TxtNovelParser.paginate(chapters[chapterIndex].content, pageTargetLength)
        }
    ): Position? {
        val pages = pagesForChapter(position.chapterIndex)
        if (position.pageIndex < pages.lastIndex) return Position(position.chapterIndex, position.pageIndex + 1)
        val nextChapter = position.chapterIndex + 1
        if (nextChapter > chapters.lastIndex) return null
        return normalizePosition(chapters, Position(nextChapter, 0), pageTargetLength, pagesForChapter)
    }

    private fun chooseTtsSplitEnd(text: String, start: Int): Int {
        val maxEnd = minOf(text.length, start + MAX_TTS_CHUNK_CHARS)
        if (maxEnd == text.length) return text.length
        val minEnd = minOf(text.length, start + MIN_TTS_CHUNK_CHARS)
        val sentenceEnd = findLastSplitChar(text, minEnd, maxEnd, SENTENCE_END_CHARS)
        if (sentenceEnd > start) return sentenceEnd
        val softEnd = findLastSplitChar(text, minEnd, maxEnd, SOFT_SPLIT_CHARS)
        if (softEnd > start) return softEnd
        return maxEnd
    }

    private fun findLastSplitChar(text: String, minEnd: Int, maxEnd: Int, chars: String): Int {
        for (index in maxEnd - 1 downTo minEnd) {
            if (text[index] in chars) return index + 1
        }
        return -1
    }
}
