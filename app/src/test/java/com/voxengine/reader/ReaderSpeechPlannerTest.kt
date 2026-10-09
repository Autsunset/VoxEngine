package com.voxengine.reader

import com.voxengine.engine.mimo.MiMoRequestFactory
import org.junit.Assert.*
import org.junit.Test

class ReaderSpeechPlannerTest {
    @Test fun contextNeverChangesSpeechOrderAcrossChunks() {
        val content = "他打开房门。\n${"不要走。".repeat(100)}\n她转身离开。"
        val chunks = ReaderSpeechPlanner.build(content, false, RoleProfile(), ReaderSynthesisOptions(chunkChars = 180))
        val requests = chunks.map { chunk ->
            MiMoRequestFactory.build(chunk.speech.text, "茉莉", context = chunk.context)
        }

        assertEquals(content.replace("\n", ""), requests.joinToString("") { it.messages[1].content })
        assertTrue(requests.all { it.messages[0].content.isEmpty() })
        assertTrue(chunks.zipWithNext().all { (left, right) -> left.end == right.start })
    }

    @Test fun speechSpansSurviveScreenPaginationAndKeepContext() {
        val content = "张三笑道：\u201c${"你好。".repeat(100)}\u201d\n李四转身离开。"
        val profile = RoleProfile(characters = mapOf("张三" to RoleVoiceStyle("苏打")))
        val chunks = ReaderSpeechPlanner.build(content, true, profile, ReaderSynthesisOptions(chunkChars = 180))
        assertEquals(content.replace("\n", ""), chunks.joinToString("") { it.speech.text })
        assertTrue(chunks.filter { it.speech.role == SpeechRole.DIALOGUE }.all { it.speech.character == "张三" })
        assertTrue(chunks.first().context!!.contains("李四转身离开。"))
        val smallPages = TxtNovelParser.paginate(content, 90)
        val largePages = TxtNovelParser.paginate(content, 520)
        for (chunk in chunks) {
            val small = ReaderSpeechPlanner.displayPosition(smallPages, chunk.start)
            val large = ReaderSpeechPlanner.displayPosition(largePages, chunk.start)
            assertTrue(small.first in smallPages.indices)
            assertTrue(large.first in largePages.indices)
        }
        assertTrue(smallPages.size > largePages.size)
        // Every synthesis chunk still comes from the original paragraph, even when its
        // dialogue crosses several display pages; cache keys therefore stay reusable.
        assertEquals(chunks, ReaderSpeechPlanner.build(content, true, profile, ReaderSynthesisOptions(chunkChars = 180)))
    }

    @Test fun pageBudgetBuffersShortDialogueAndRefillsAfterConsumption() {
        val text = (1..30).joinToString("\n") { "第${it}段。" }
        val chunks = ReaderSpeechPlanner.build(text, false, RoleProfile(), ReaderSynthesisOptions())
        val firstEnd = ReaderSpeechPlanner.windowEnd(chunks, 0, 40)
        assertTrue(firstEnd > 3)
        assertTrue(ReaderSpeechPlanner.windowEnd(chunks, 4, 40) > firstEnd)
        assertTrue(ReaderSpeechPlanner.build(text, false, RoleProfile(), ReaderSynthesisOptions(contextEnabled = false)).all { it.context == null })
    }

    @Test fun sourcePositionRoundTripsAtParagraphStarts() {
        val pages = listOf(TxtPage(listOf("甲乙", "丙丁戊")), TxtPage(listOf("己庚")))
        assertEquals(5, ReaderSpeechPlanner.offsetFor(pages, 1, 0))
        assertEquals(1 to 0, ReaderSpeechPlanner.displayPosition(pages, 5))
        assertEquals(0 to 1, ReaderSpeechPlanner.displayPosition(pages, 2))
    }
}
