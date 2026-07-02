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

    /**
     * \u6309\u4E2D\u82F1\u6587\u53E5\u672B\u6807\u70B9\uFF08\u3002\uFF01\uFF1F\uFF1B! ? ; \u53CA\u6362\u884C\uFF09\u628A\u4E00\u6BB5\u6587\u672C\u5207\u6210\u53EF\u72EC\u7ACB\u5408\u6210\u7684\u53E5\u5B50\u3002
     * \u5148 normalize\uFF0C\u518D\u9010\u5B57\u626B\u63CF\uFF1B\u8DF3\u8FC7\u53EA\u542B\u5F15\u53F7/\u7B26\u53F7\u7684\u7A7A\u6BB5\uFF0C\u4FDD\u7559\u6709\u5B57\u6BCD\u6570\u5B57\u7684\u6BB5\u843D\u3002
     * \u6574\u6BB5\u65E0\u53EF\u5207\u5206\u5185\u5BB9\u65F6\u56DE\u9000\u4E3A\u6574\u6BB5\uFF08\u4ECD\u4FDD\u8BC1\u8FD4\u56DE\u975E\u7A7A\u3001\u53EF\u9001\u5408\u6210\uFF09\u3002
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
                if (s.isNotEmpty() && s.any { it.isLetterOrDigit() }) {
                    sentences.add(s)
                }
                sb.clear()
            }
        }
        val remaining = sb.toString().trim()
        if (remaining.isNotEmpty() && remaining.any { it.isLetterOrDigit() }) {
            sentences.add(remaining)
        }
        return sentences.ifEmpty { listOf(normalizedText) }
    }
}
