package com.voxengine.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtNovelParserTest {
    @Test
    fun parseRecognizesChineseChapterHeadings() {
        val text = """
            第1章 初见
            这是第一章第一段。这里加入足够长的正文内容，用来满足章节标题之间的最小距离过滤规则，避免短样本被当作误识别标题处理。

            这是第一章第二段。这里继续补充正文，让两个章节标题之间超过一百个字符，从而测试真实小说文本中的章节识别路径。

            第二章 重逢
            这是第二章。
        """.trimIndent()

        val chapters = TxtNovelParser.parse(text)

        assertEquals(2, chapters.size)
        assertEquals("第1章 初见", chapters[0].title)
        assertTrue(chapters[0].content.contains("第一章第一段"))
        assertEquals("第二章 重逢", chapters[1].title)
    }

    @Test
    fun parseFallsBackForTextWithoutHeadings() {
        val text = "一段没有章节标题的正文。".repeat(800)

        val chapters = TxtNovelParser.parse(text)

        assertTrue(chapters.isNotEmpty())
        assertEquals("第1章", chapters.first().title)
        assertTrue(chapters.joinToString("") { it.content }.contains("没有章节标题"))
    }

    @Test
    fun paginatePreservesParagraphOrder() {
        val content = listOf(
            "第一段".repeat(30),
            "第二段".repeat(30),
            "第三段".repeat(30)
        ).joinToString("\n")

        val pages = TxtNovelParser.paginate(content, targetLength = 120)
        val combined = pages.flatMap { it.paragraphs }.joinToString("")

        assertTrue(pages.size > 1)
        assertEquals(content.replace("\n", ""), combined)
    }

    @Test
    fun paginateDoesNotSplitEmojiSurrogatePair() {
        // targetLength=120 时内部段长为 102，令 emoji 横跨原切点。
        val content = "字".repeat(101) + "😀" + "后文".repeat(80)

        val paragraphs = TxtNovelParser.paginate(content, targetLength = 120).flatMap { it.paragraphs }

        assertEquals(content, paragraphs.joinToString(separator = ""))
        assertTrue(paragraphs.none { it.lastOrNull()?.isHighSurrogate() == true })
        assertTrue(paragraphs.none { it.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test
    fun decodeSupportsUtf8Bom() {
        val payload = "正文内容"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + payload.toByteArray(Charsets.UTF_8)

        assertEquals(payload, TxtNovelParser.decode(bytes))
    }

    @Test
    fun decodeSupportsUtf16BomWithoutLeakingBom() {
        val payload = "正文内容"
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + payload.toByteArray(Charsets.UTF_16LE)
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + payload.toByteArray(Charsets.UTF_16BE)

        assertEquals(payload, TxtNovelParser.decode(littleEndian))
        assertEquals(payload, TxtNovelParser.decode(bigEndian))
    }
}
