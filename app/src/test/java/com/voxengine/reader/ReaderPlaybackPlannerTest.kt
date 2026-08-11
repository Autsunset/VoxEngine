package com.voxengine.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPlaybackPlannerTest {
    @Test
    fun splitTextForTtsPreservesSourceTextAndCapsChunkSize() {
        val text = "这是一段很长的正文，".repeat(45) + "最后一句结束。"

        val chunks = ReaderPlaybackPlanner.splitTextForTts(text)

        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { it.length <= ReaderPlaybackPlanner.MAX_TTS_CHUNK_CHARS })
    }

    @Test
    fun splitTextForTtsDoesNotSplitEmojiSurrogatePair() {
        val text = "字".repeat(179) + "😀" + "结尾".repeat(20)

        val chunks = ReaderPlaybackPlanner.splitTextForTts(text)

        assertEquals(text, chunks.joinToString(separator = ""))
        assertTrue(chunks.none { it.lastOrNull()?.isHighSurrogate() == true })
        assertTrue(chunks.none { it.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test
    fun normalizePositionSkipsEmptyChaptersAndClampsPageIndex() {
        val chapters = listOf(
            TxtChapter("空章", ""),
            TxtChapter("第一章", "正文"),
            TxtChapter("第二章", "正文")
        )
        val pages = mapOf(
            0 to emptyList<TxtPage>(),
            1 to listOf(TxtPage(listOf("p0")), TxtPage(listOf("p1"))),
            2 to listOf(TxtPage(listOf("p2")))
        )

        val position = ReaderPlaybackPlanner.normalizePosition(
            chapters = chapters,
            position = ReaderPlaybackPlanner.Position(0, 99),
            pageTargetLength = 220,
            pagesForChapter = { pages.getValue(it) }
        )

        assertEquals(ReaderPlaybackPlanner.Position(1, 1), position)
    }

    @Test
    fun nextPositionAdvancesWithinChapterThenAcrossChapter() {
        val chapters = listOf(TxtChapter("第一章", "正文"), TxtChapter("第二章", "正文"))
        val pages = mapOf(
            0 to listOf(TxtPage(listOf("a")), TxtPage(listOf("b"))),
            1 to listOf(TxtPage(listOf("c")))
        )
        val pagesForChapter: (Int) -> List<TxtPage> = { pages.getValue(it) }

        assertEquals(
            ReaderPlaybackPlanner.Position(0, 1),
            ReaderPlaybackPlanner.nextPosition(chapters, ReaderPlaybackPlanner.Position(0, 0), 220, pagesForChapter)
        )
        assertEquals(
            ReaderPlaybackPlanner.Position(1, 0),
            ReaderPlaybackPlanner.nextPosition(chapters, ReaderPlaybackPlanner.Position(0, 1), 220, pagesForChapter)
        )
        assertEquals(
            null,
            ReaderPlaybackPlanner.nextPosition(chapters, ReaderPlaybackPlanner.Position(1, 0), 220, pagesForChapter)
        )
    }

    @Test
    fun targetChapterRejectsMediaKeyMovesPastBookBounds() {
        assertEquals(1, ReaderPlaybackPlanner.targetChapter(0, 1, 3))
        assertEquals(null, ReaderPlaybackPlanner.targetChapter(0, -1, 3))
        assertEquals(null, ReaderPlaybackPlanner.targetChapter(2, 1, 3))
        assertEquals(null, ReaderPlaybackPlanner.targetChapter(0, Int.MAX_VALUE, 0))
    }

    @Test
    fun buildPrefetchWindowStartsAtParagraphAndIncludesRequestedNextChapterPages() {
        val chapters = listOf(TxtChapter("第一章", "正文"), TxtChapter("第二章", "正文"))
        val pages = mapOf(
            0 to listOf(TxtPage(listOf("a0", "a1")), TxtPage(listOf("b0"))),
            1 to listOf(TxtPage(listOf("c0")), TxtPage(listOf("d0")))
        )

        val window = ReaderPlaybackPlanner.buildPrefetchWindow(
            chapters = chapters,
            currentPosition = ReaderPlaybackPlanner.Position(0, 0),
            startParagraphIndex = 1,
            nextChapterPrefetchPageCount = 1,
            pageTargetLength = 220,
            pagesForChapter = { pages.getValue(it) }
        )

        assertEquals(listOf("a1", "b0", "c0"), window.map { it.second })
        assertEquals(
            listOf(
                ReaderPlaybackPlanner.ChunkKey(ReaderPlaybackPlanner.Position(0, 0), 1, 0),
                ReaderPlaybackPlanner.ChunkKey(ReaderPlaybackPlanner.Position(0, 1), 0, 0),
                ReaderPlaybackPlanner.ChunkKey(ReaderPlaybackPlanner.Position(1, 0), 0, 0)
            ),
            window.map { it.first }
        )
    }

    @Test
    fun buildPrefetchWindowRespectsMaxChunks() {
        val chapters = listOf(TxtChapter("第一章", "正文"), TxtChapter("第二章", "正文"))
        val pages = mapOf(
            0 to listOf(TxtPage(listOf("a0", "a1", "a2")), TxtPage(listOf("b0", "b1"))),
            1 to listOf(TxtPage(listOf("c0")))
        )

        val full = ReaderPlaybackPlanner.buildPrefetchWindow(
            chapters = chapters,
            currentPosition = ReaderPlaybackPlanner.Position(0, 0),
            startParagraphIndex = 0,
            nextChapterPrefetchPageCount = 1,
            pageTargetLength = 220,
            pagesForChapter = { pages.getValue(it) }
        )
        val capped = ReaderPlaybackPlanner.buildPrefetchWindow(
            chapters = chapters,
            currentPosition = ReaderPlaybackPlanner.Position(0, 0),
            startParagraphIndex = 0,
            nextChapterPrefetchPageCount = 1,
            pageTargetLength = 220,
            maxChunks = 2,
            pagesForChapter = { pages.getValue(it) }
        )

        assertTrue(full.size > 2)
        assertEquals(2, capped.size)
        // 截断保留最靠前的 chunk，顺序与起点不变
        assertEquals(full.take(2), capped)
    }

    @Test
    fun buildPrefetchWindowStopsReadingPagesAfterChunkLimit() {
        val chapters = listOf(TxtChapter("第一章", "正文"), TxtChapter("第二章", "正文"))
        val pages = mapOf(
            0 to listOf(
                TxtPage(listOf("a0", "a1", "a2")),
                TxtPage(listOf("unused-current-page"))
            ),
            1 to listOf(TxtPage(listOf("unused-next-chapter")))
        )
        val calls = mutableListOf<Int>()

        val window = ReaderPlaybackPlanner.buildPrefetchWindow(
            chapters = chapters,
            currentPosition = ReaderPlaybackPlanner.Position(0, 0),
            startParagraphIndex = 0,
            nextChapterPrefetchPageCount = 1,
            pageTargetLength = 220,
            maxChunks = 2,
            pagesForChapter = { chapterIndex ->
                calls += chapterIndex
                pages.getValue(chapterIndex)
            }
        )

        assertEquals(listOf("a0", "a1"), window.map { it.second })
        assertEquals(listOf(0), calls)
    }

    @Test
    fun buildPrefetchWindowWithZeroLimitDoesNotPaginate() {
        val chapters = listOf(TxtChapter("第一章", "正文"))
        var pageRequests = 0

        val window = ReaderPlaybackPlanner.buildPrefetchWindow(
            chapters = chapters,
            currentPosition = ReaderPlaybackPlanner.Position(0, 0),
            startParagraphIndex = 0,
            nextChapterPrefetchPageCount = 0,
            pageTargetLength = 220,
            maxChunks = 0,
            pagesForChapter = {
                pageRequests += 1
                listOf(TxtPage(listOf("不应访问")))
            }
        )

        assertTrue(window.isEmpty())
        assertEquals(0, pageRequests)
    }

    @Test
    fun chunkKeysSkipsPureSymbolParagraphsAndKeepsText() {
        val chapters = listOf(TxtChapter("第一章", "正文"))
        val pages = mapOf(
            0 to listOf(TxtPage(listOf("你好。", "★★★★★★", "。。。。。", "世界。")))
        )

        val chunks = ReaderPlaybackPlanner.chunkKeysForPlayback(
            chapters = chapters,
            position = ReaderPlaybackPlanner.Position(0, 0),
            startParagraphIndex = 0,
            pageTargetLength = 220,
            pagesForChapter = { pages.getValue(it) }
        )

        // 纯符号段被跳过，只保留含字/数字的段落
        assertEquals(listOf("你好。", "世界。"), chunks.map { it.second })
        // 段落下标从 0 跳到 3（中间两段纯符号被丢弃）；chunkIndex 每段内从 0 起
        assertEquals(
            listOf(
                ReaderPlaybackPlanner.ChunkKey(ReaderPlaybackPlanner.Position(0, 0), 0, 0),
                ReaderPlaybackPlanner.ChunkKey(ReaderPlaybackPlanner.Position(0, 0), 3, 0)
            ),
            chunks.map { it.first }
        )
    }

    @Test
    fun chunkKeysRoleAwareSkipsPureSymbolParagraphs() {
        val chapters = listOf(TxtChapter("第一章", "正文"))
        val pages = mapOf(
            0 to listOf(TxtPage(listOf("她笑了。", "——————", "他点头。")))
        )

        val chunks = ReaderPlaybackPlanner.chunkKeysForPlaybackRoleAware(
            chapters = chapters,
            position = ReaderPlaybackPlanner.Position(0, 0),
            startParagraphIndex = 0,
            pageTargetLength = 220,
            pagesForChapter = { pages.getValue(it) }
        )

        // 纯符号分隔行被跳过；有字段落保留（角色路由不影响过滤）
        val texts = chunks.map { it.second.text }
        assertTrue(texts.none { it.none { c -> c.isLetterOrDigit() } })
        assertTrue(texts.any { it.contains("她笑了") })
        assertTrue(texts.any { it.contains("他点头") })
    }
}
