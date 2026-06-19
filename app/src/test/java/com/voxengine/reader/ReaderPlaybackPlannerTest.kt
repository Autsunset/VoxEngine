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
}
