package com.voxengine.util

object SpeechTextNormalizer {
    private val wrapperPairs = mapOf(
        '【' to '】',
        '[' to ']',
        '［' to '］',
        '《' to '》',
        '<' to '>',
        '〈' to '〉'
    )
    private val quoteWrappers = wrapperPairs.keys + wrapperPairs.values

    fun normalize(text: String): String {
        if (text.none { it in quoteWrappers }) return text
        val normalized = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val opening = text[index]
            val closing = wrapperPairs[opening]
            if (closing == null) {
                normalized.append(opening)
                index += 1
                continue
            }

            val closingIndex = text.indexOf(closing, startIndex = index + 1)
            if (closingIndex <= index) {
                normalized.append(opening)
                index += 1
                continue
            }

            val inner = text.substring(index + 1, closingIndex)
            val useChineseQuotes = inner.any { it.isCjk() }
            normalized.append(if (useChineseQuotes) '“' else '"')
            normalized.append(inner)
            normalized.append(if (useChineseQuotes) '”' else '"')
            index = closingIndex + 1
        }

        return normalized.toString()
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""[ \t]+([，。！？；：,.!?;:])"""), "\$1")
            .replace(Regex("""([，。！？；：,.!?;:])[ \t]+"""), "\$1")
            .trim()
            .ifBlank { text }
    }

    private fun Char.isCjk(): Boolean =
        this in '\u4E00'..'\u9FFF' ||
            this in '\u3400'..'\u4DBF' ||
            this in '\uF900'..'\uFAFF'
}
