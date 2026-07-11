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

    /** 是否包含可朗读的字母/数字。纯标点、省略号等返回 false，供引擎短路为静音。 */
    fun hasSpeakableContent(text: String): Boolean = text.any { it.isLetterOrDigit() }

    private fun Char.isCjk(): Boolean =
        this in '\u4E00'..'\u9FFF' ||
            this in '\u3400'..'\u4DBF' ||
            this in '\uF900'..'\uFAFF'

    /**
     * 按中英文句末标点（。！？；! ? ; 及换行）把一段文本切成可独立合成的句子。
     * 先 normalize，再逐字扫描；跳过只含引号/符号的空段，保留有字母数字的段落。
     * 整段无可切分内容时回退为整段（仍保证返回非空、可送合成）。
     */
    fun splitSentences(text: String): List<String> {
        val normalizedText = normalize(text)
        val terminators = charArrayOf('\u3002', '\uFF01', '\uFF1F', '\uFF1B', '!', '?', ';', '\n')
        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in normalizedText) {
            sb.append(ch)
            if (ch in terminators) {
                val s = sb.toString().trim()
                if (s.isNotEmpty() && hasSpeakableContent(s)) {
                    sentences.add(s)
                }
                sb.clear()
            }
        }
        val remaining = sb.toString().trim()
        if (remaining.isNotEmpty() && hasSpeakableContent(remaining)) {
            sentences.add(remaining)
        }
        return sentences.ifEmpty { listOf(normalizedText) }
    }
}
