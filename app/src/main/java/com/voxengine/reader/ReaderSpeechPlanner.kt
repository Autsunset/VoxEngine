package com.voxengine.reader

import com.voxengine.util.SpeechTextNormalizer

/** Speech boundaries depend on the source paragraphs, never the current screen size. */
object ReaderSpeechPlanner {
    data class Chunk(
        val paragraphIndex: Int,
        val start: Int,
        val end: Int,
        val speech: ReaderPlaybackPlanner.RoleChunk,
        val context: String?
    )

    fun build(content: String, roleEnabled: Boolean, profile: RoleProfile, options: ReaderSynthesisOptions): List<Chunk> {
        val paragraphs = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val chunks = mutableListOf<Chunk>()
        var chapterOffset = 0
        paragraphs.forEachIndexed { index, paragraph ->
            val normalized = SpeechTextNormalizer.normalize(paragraph)
            val spans = if (roleEnabled) RoleSegmenter.segment(normalized, profile.characters.keys, profile.matchRules)
                else listOf(RoleSegment(SpeechRole.NARRATION, null, normalized))
            val context = if (options.contextEnabled) listOf(
                paragraphs.getOrNull(index - 1).orEmpty().takeLast(350), paragraph.take(500),
                paragraphs.getOrNull(index + 1).orEmpty().take(350)
            ).filter { it.isNotBlank() }.joinToString("\n") else null
            var offset = 0
            for (span in spans) {
                offset = normalized.indexOf(span.text, offset).takeIf { it >= 0 } ?: offset
                for (part in ReaderPlaybackPlanner.splitTextForTts(span.text, options.chunkChars)) {
                    if (SpeechTextNormalizer.hasSpeakableContent(part)) {
                        // Translate normalized indices back to the source paragraph for progress.
                        val start = chapterOffset + (offset.toLong() * paragraph.length / normalized.length.coerceAtLeast(1)).toInt()
                        val end = chapterOffset + ((offset + part.length).toLong() * paragraph.length / normalized.length.coerceAtLeast(1)).toInt()
                        chunks += Chunk(index, start, end, ReaderPlaybackPlanner.RoleChunk(span.role, span.character, part), context)
                    }
                    offset += part.length
                }
            }
            chapterOffset += paragraph.length
        }
        return chunks
    }

    fun offsetFor(pages: List<TxtPage>, pageIndex: Int, paragraphIndex: Int): Int =
        pages.take(pageIndex.coerceAtLeast(0)).sumOf { page -> page.paragraphs.sumOf { it.length } } +
            pages.getOrNull(pageIndex)?.paragraphs.orEmpty().take(paragraphIndex.coerceAtLeast(0)).sumOf { it.length }

    fun displayPosition(pages: List<TxtPage>, offset: Int): Pair<Int, Int> {
        var remaining = offset.coerceAtLeast(0)
        pages.forEachIndexed { pageIndex, page ->
            page.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                if (remaining < paragraph.length) return pageIndex to paragraphIndex
                remaining -= paragraph.length
            }
        }
        return pages.lastIndex.coerceAtLeast(0) to (pages.lastOrNull()?.paragraphs?.size ?: 0)
    }

    fun windowEnd(chunks: List<Chunk>, start: Int, characterBudget: Int): Int {
        var end = start
        var chars = 0
        while (end < chunks.size && end - start < 64) {
            chars += chunks[end].speech.text.length
            end++
            if (chars >= characterBudget.coerceAtLeast(1)) break
        }
        return end
    }
}
