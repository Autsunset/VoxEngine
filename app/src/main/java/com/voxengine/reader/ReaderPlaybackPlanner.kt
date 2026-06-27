package com.voxengine.reader

object ReaderPlaybackPlanner {
    const val MIN_TTS_CHUNK_CHARS = 40
    const val MAX_TTS_CHUNK_CHARS = 180
    const val MAX_PREFETCH_AHEAD = 8

    private const val SENTENCE_END_CHARS = "。！？；.!?;"
    private const val SOFT_SPLIT_CHARS = "，、,：:"

    data class Position(val chapterIndex: Int, val pageIndex: Int)
    data class ChunkKey(val position: Position, val paragraphIndex: Int, val chunkIndex: Int)

    /**
     * 一个待合成片段：携带 [role]/[character]（分角色路由用）与 [text]（已按长度切分）。
     * 角色关闭时所有片段均为 [SpeechRole.NARRATION]、character=null，退化为单音色行为。
     */
    data class RoleChunk(val role: SpeechRole, val character: String?, val text: String)

    fun buildPrefetchWindow(
        chapters: List<TxtChapter>,
        currentPosition: Position,
        startParagraphIndex: Int,
        nextChapterPrefetchPageCount: Int,
        pageTargetLength: Int,
        maxChunks: Int = Int.MAX_VALUE,
        pagesForChapter: (Int) -> List<TxtPage> = { chapterIndex ->
            TxtNovelParser.paginate(chapters[chapterIndex].content, pageTargetLength)
        }
    ): List<Pair<ChunkKey, String>> = buildPrefetchWindowCore(
        chapters, currentPosition, startParagraphIndex, nextChapterPrefetchPageCount,
        pageTargetLength, maxChunks, pagesForChapter, roleEnabled = false, configuredNames = emptySet()
    ).map { (key, chunk) -> key to chunk.text }

    /** 角色感知版预取窗口：返回 [RoleChunk]，供听书服务按角色解析音色。 */
    fun buildPrefetchWindowRoleAware(
        chapters: List<TxtChapter>,
        currentPosition: Position,
        startParagraphIndex: Int,
        nextChapterPrefetchPageCount: Int,
        pageTargetLength: Int,
        maxChunks: Int = Int.MAX_VALUE,
        pagesForChapter: (Int) -> List<TxtPage> = { chapterIndex ->
            TxtNovelParser.paginate(chapters[chapterIndex].content, pageTargetLength)
        },
        configuredNames: Set<String> = emptySet()
    ): List<Pair<ChunkKey, RoleChunk>> = buildPrefetchWindowCore(
        chapters, currentPosition, startParagraphIndex, nextChapterPrefetchPageCount,
        pageTargetLength, maxChunks, pagesForChapter, roleEnabled = true, configuredNames = configuredNames
    )

    private fun buildPrefetchWindowCore(
        chapters: List<TxtChapter>,
        currentPosition: Position,
        startParagraphIndex: Int,
        nextChapterPrefetchPageCount: Int,
        pageTargetLength: Int,
        maxChunks: Int,
        pagesForChapter: (Int) -> List<TxtPage>,
        roleEnabled: Boolean,
        configuredNames: Set<String> = emptySet()
    ): List<Pair<ChunkKey, RoleChunk>> {
        val window = buildList {
            val currentChapterPages = pagesForChapter(currentPosition.chapterIndex)
            for (pageIndex in currentPosition.pageIndex until currentChapterPages.size) {
                addAll(
                    chunkKeysCore(
                        chapters = chapters,
                        position = Position(currentPosition.chapterIndex, pageIndex),
                        startParagraphIndex = if (pageIndex == currentPosition.pageIndex) startParagraphIndex else 0,
                        pageTargetLength = pageTargetLength,
                        pagesForChapter = pagesForChapter,
                        roleEnabled = roleEnabled,
                        configuredNames = configuredNames
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
                        chunkKeysCore(
                            chapters = chapters,
                            position = Position(nextChapterPosition.chapterIndex, pageIndex),
                            startParagraphIndex = 0,
                            pageTargetLength = pageTargetLength,
                            pagesForChapter = pagesForChapter,
                            roleEnabled = roleEnabled,
                            configuredNames = configuredNames
                        )
                    )
                }
            }
        }
        return if (maxChunks < window.size) window.take(maxChunks.coerceAtLeast(0)) else window
    }

    fun chunkKeysForPlayback(
        chapters: List<TxtChapter>,
        position: Position,
        startParagraphIndex: Int,
        pageTargetLength: Int,
        pagesForChapter: (Int) -> List<TxtPage> = { chapterIndex ->
            TxtNovelParser.paginate(chapters[chapterIndex].content, pageTargetLength)
        }
    ): List<Pair<ChunkKey, String>> =
        chunkKeysCore(chapters, position, startParagraphIndex, pageTargetLength, pagesForChapter, roleEnabled = false, configuredNames = emptySet())
            .map { (key, chunk) -> key to chunk.text }

    /** 角色感知版：每段先经 [RoleSegmenter.segment] 切旁白/对话，再按长度切分，片段携带角色。 */
    fun chunkKeysForPlaybackRoleAware(
        chapters: List<TxtChapter>,
        position: Position,
        startParagraphIndex: Int,
        pageTargetLength: Int,
        pagesForChapter: (Int) -> List<TxtPage> = { chapterIndex ->
            TxtNovelParser.paginate(chapters[chapterIndex].content, pageTargetLength)
        },
        configuredNames: Set<String> = emptySet()
    ): List<Pair<ChunkKey, RoleChunk>> =
        chunkKeysCore(chapters, position, startParagraphIndex, pageTargetLength, pagesForChapter, roleEnabled = true, configuredNames = configuredNames)

    private fun chunkKeysCore(
        chapters: List<TxtChapter>,
        position: Position,
        startParagraphIndex: Int,
        pageTargetLength: Int,
        pagesForChapter: (Int) -> List<TxtPage>,
        roleEnabled: Boolean,
        configuredNames: Set<String> = emptySet()
    ): List<Pair<ChunkKey, RoleChunk>> {
        val page = pagesForChapter(position.chapterIndex).getOrNull(position.pageIndex)
            ?: return emptyList()
        val startIndex = startParagraphIndex.coerceIn(0, page.paragraphs.size)
        return page.paragraphs.drop(startIndex).flatMapIndexed { offset, paragraph ->
            val paragraphIndex = startIndex + offset
            // 角色关闭：整段作为单个 NARRATION 片段 → splitTextForTts 行为与历史完全一致。
            val spans = if (roleEnabled) RoleSegmenter.segment(paragraph, configuredNames)
            else listOf(RoleSegment(SpeechRole.NARRATION, null, paragraph))
            val chunks = mutableListOf<Pair<ChunkKey, RoleChunk>>()
            var chunkIndex = 0
            for (span in spans) {
                for (part in splitTextForTts(span.text)) {
                    chunks += ChunkKey(position, paragraphIndex, chunkIndex) to
                        RoleChunk(span.role, span.character, part)
                    chunkIndex += 1
                }
            }
            chunks
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
