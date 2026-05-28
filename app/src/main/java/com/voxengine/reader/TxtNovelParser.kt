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
                bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charset.forName("UTF-16LE"))
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charset.forName("UTF-16BE"))
            else -> runCatching { bytes.toString(Charsets.UTF_8) }
                .getOrElse { bytes.toString(Charset.forName("GB18030")) }
                .let { decoded ->
                    if (decoded.count { it == '\uFFFD' } > decoded.length / 100) {
                        bytes.toString(Charset.forName("GB18030"))
                    } else {
                        decoded
                    }
                }
        }.normalizeText()
    }

    fun parse(text: String): List<TxtChapter> {
        val normalized = text.normalizeText()
        if (normalized.isBlank()) return emptyList()

        val lines = normalized.split('\n')
        val headings = mutableListOf<Heading>()
        var offset = 0
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.length in 2..64 && chapterRules.any { it.matches(trimmed) }) {
                headings += Heading(trimmed, offset, offset + line.length, volumeRule.matches(trimmed))
            }
            offset += line.length + 1
        }

        val filtered = headings.filterIndexed { index, heading ->
            index == 0 || heading.start - headings[index - 1].start > 100
        }

        return if (filtered.size >= 2) {
            buildChaptersFromHeadings(normalized, filtered)
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

        fun partCost(part: String): Int = part.length + paragraphGapCost

        fun flushPage() {
            if (current.isNotEmpty()) {
                pages += TxtPage(current.toList())
                current.clear()
                currentCost = 0
            }
        }

        paragraphs.forEach { paragraph ->
            val parts = paragraph.chunked(chunkLength)
            parts.forEach { part ->
                val cost = partCost(part)
                if (current.isNotEmpty() && currentCost + cost > safeTargetLength) {
                    flushPage()
                }
                current += part
                currentCost += cost
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
                .replace(Regex("""^[\n\s]+"""), "")
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
        split(Regex("""\n{1,}"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

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
