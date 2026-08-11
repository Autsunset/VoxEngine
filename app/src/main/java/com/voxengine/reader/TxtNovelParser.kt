package com.voxengine.reader

import java.nio.charset.Charset

data class TxtChapter(
    val title: String,
    val content: String,
    val isVolume: Boolean = false
)

data class TxtPage(
    val paragraphs: List<String>
) {
    val text: String = paragraphs.joinToString("\n\n")
}

object TxtNovelParser {
    private const val PAGE_TARGET_LENGTH = 220
    private const val FALLBACK_CHAPTER_LENGTH = 10_000
    private val gb18030: Charset = Charset.forName("GB18030")

    private val chapterRules = listOf(
        Regex("""^[　 \t]{0,4}(?:序章|楔子|引子|尾声|后记|正文卷|作品相关|番外.{0,40})$"""),
        Regex("""^[　 \t]{0,4}第[\d０-９〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,10}[章节卷集部篇回][\s　:：、,.，._-]{0,4}.{0,50}$"""),
        Regex("""^[　 \t]{0,4}\d{1,5}(?:[、:：.,，　 \t_-]+|\s+).{1,45}$"""),
        Regex("""^[　 \t]{0,4}(?:Chapter|Section|Part|Episode)\s{0,4}\d{1,5}.{0,45}$""", RegexOption.IGNORE_CASE),
        Regex("""^[一-龥]{1,20}[　 \t]{0,4}[\(（][\d０-９〇零一二两三四五六七八九十百千万]{1,8}[\)）][　 \t]{0,4}$""")
    )

    private val volumeRule = Regex("""^[　 \t]{0,4}(?:正文卷|作品相关|第[\d０-９〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]{1,10}[卷集部].{0,40})$""")

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> runCatching { bytes.toString(Charsets.UTF_8) }
                .getOrElse { bytes.toString(gb18030) }
                .let { decoded ->
                    if (decoded.count { it == '\uFFFD' } > decoded.length / 100) {
                        bytes.toString(gb18030)
                    } else {
                        decoded
                    }
                }
        }.normalizeText()
    }

    fun parse(text: String): List<TxtChapter> {
        val normalized = text.normalizeText()
        if (normalized.isBlank()) return emptyList()

        val headings = mutableListOf<Heading>()
        var offset = 0
        var previousHeadingStart: Int? = null
        normalized.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.length in 2..64 && chapterRules.any { it.matches(trimmed) }) {
                val heading = Heading(trimmed, offset, offset + line.length, volumeRule.matches(trimmed))
                val previousStart = previousHeadingStart
                if (previousStart == null || heading.start - previousStart > 100) {
                    headings += heading
                }
                previousHeadingStart = heading.start
            }
            offset += line.length + 1
        }

        return if (headings.size >= 2) {
            buildChaptersFromHeadings(normalized, headings)
        } else {
            splitFallback(normalized)
        }
    }

    fun paginate(content: String, targetLength: Int = PAGE_TARGET_LENGTH): List<TxtPage> {
        val paragraphs = content.toParagraphs()
        if (paragraphs.isEmpty()) return listOf(TxtPage(listOf(content.trim())).takeIf { content.isNotBlank() } ?: TxtPage(emptyList()))

        val safeTargetLength = targetLength.coerceIn(90, 520)
        val paragraphGapCost = (safeTargetLength / 8).coerceIn(18, 48)
        val chunkLength = (safeTargetLength - paragraphGapCost).coerceIn(70, 260)
        val pages = mutableListOf<TxtPage>()
        val current = mutableListOf<String>()
        var currentCost = 0

        fun flushPage() {
            if (current.isNotEmpty()) {
                pages += TxtPage(current.toList())
                current.clear()
                currentCost = 0
            }
        }

        paragraphs.forEach { paragraph ->
            var start = 0
            while (start < paragraph.length) {
                var end = minOf(start + chunkLength, paragraph.length)
                if (end < paragraph.length && paragraph[end - 1].isHighSurrogate() && paragraph[end].isLowSurrogate()) {
                    end -= 1
                }
                val part = paragraph.substring(start, end)
                val cost = part.length + paragraphGapCost
                if (current.isNotEmpty() && currentCost + cost > safeTargetLength) {
                    flushPage()
                }
                current += part
                currentCost += cost
                start = end
            }
        }
        flushPage()
        return pages.ifEmpty { listOf(TxtPage(emptyList())) }
    }

    private fun buildChaptersFromHeadings(text: String, headings: List<Heading>): List<TxtChapter> {
        val chapters = mutableListOf<TxtChapter>()
        if (headings.first().start > 100) {
            chapters += TxtChapter("前言", text.substring(0, headings.first().start).trim())
        }

        headings.forEachIndexed { index, heading ->
            val end = headings.getOrNull(index + 1)?.start ?: text.length
            val body = text.substring(heading.titleEnd, end)
                .trim()
            chapters += TxtChapter(heading.title, body, heading.isVolume || body.isBlank())
        }
        return chapters.filter { it.content.isNotBlank() || it.isVolume }.ifEmpty { splitFallback(text) }
    }

    private fun splitFallback(text: String): List<TxtChapter> {
        val chapters = mutableListOf<TxtChapter>()
        var start = 0
        var index = 1
        while (start < text.length) {
            var end = minOf(start + FALLBACK_CHAPTER_LENGTH, text.length)
            if (end < text.length) {
                val nextBreak = text.indexOf('\n', end)
                if (nextBreak in end until minOf(text.length, end + 1200)) {
                    end = nextBreak
                }
            }
            val content = text.substring(start, end).trim()
            if (content.isNotBlank()) {
                chapters += TxtChapter("第${index}章", content)
                index += 1
            }
            start = end
        }
        return chapters
    }

    private fun String.toParagraphs(): List<String> =
        lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun String.normalizeText(): String =
        replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u0000', ' ')

    private data class Heading(
        val title: String,
        val start: Int,
        val titleEnd: Int,
        val isVolume: Boolean
    )
}
