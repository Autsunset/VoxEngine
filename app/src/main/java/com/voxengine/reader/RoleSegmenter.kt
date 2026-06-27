package com.voxengine.reader

import com.voxengine.util.SpeechTextNormalizer

/**
 * 把一段小说文本切成有序的“旁白 / 对话”片段，供听书分角色朗读时路由到不同音色。
 *
 * 纯函数、无 Android 依赖，便于单测（见 [RoleSegmenterTest]）。流程：
 * 1. 先 [SpeechTextNormalizer.normalize]（幂等）——把 【】[]《》 等统一成中文引号，让状态机只需认一种边界。
 * 2. 引号状态机：引号外→旁白，引号内→对话；片段拼接回去等于 normalize 后的原文本（无损）。
 * 3. 进入对话时，回看前一段旁白尾部，用正则抓说话人名（如“张三笑道：”→张三），命中则记到 [RoleSegment.character]。
 *    未命中或名字未在角色档里配置时，由上层回落到对话默认音色，不影响文本正确性。
 *
 * 注意：本对象只负责“切角色片段”；按长度二次切分（TTS 单次上限）由 [ReaderPlaybackPlanner.splitTextForTts] 负责。
 */
enum class SpeechRole { NARRATION, DIALOGUE }

data class RoleSegment(
    val role: SpeechRole,
    val character: String?,
    val text: String
)

object RoleSegmenter {
    // 对话边界引号。normalize 后为中文引号（U+201C/U+201D），另兼容直引号与日文「」『』。
    // U+201C/U+201D 是方向性的：左引号只开、右引号只闭；直引号 " 既开又闭，由状态机区分。
    // 用显式 \u 转义书写，避免源码里弯引号与 ASCII 引号视觉混淆。
    private const val OPEN_QUOTES = "“「『‘\""
    private const val CLOSE_QUOTES = "”」』’\""

    /**
     * 说话人前缀正则：对话开引号之前的旁白尾部，形如「张三笑道：」「她道」「老李怒道」。
     * 结构 = 名字(1-10 中日韩/拉丁) + 可选情绪副词 + 可选“着/了” + 说类动词 + 可选“道/说/着”后缀 + 可选冒号 + 行尾。
     */
    private val SPEAKER_PATTERN = Regex(
        "([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z·•]{0,9}?)" + // 名字（非贪婪，避免吃掉情绪副词"笑"）
            "\\s*(?:笑|怒|冷|轻|低|沉|淡|幽|懒|惊|淡然)?" +            // 可选情绪/语态
            "(?:着|了)?" +                                            // 可选动态助词
            "(?:说|道|问|答|喊|叫|骂|吼|喝|哼|嚷|喃|咕)" +             // 说类动词
            "(?:道|说|着)?" +                                         // 可选后缀
            "\\s*[：:]?\\s*$"
    )

    /** 切分文本为有序角色片段。各片段 text 拼接等于 normalize 后的原文本（无损）。 */
    fun segment(text: String): List<RoleSegment> {
        val normalized = SpeechTextNormalizer.normalize(text)
        if (normalized.isEmpty()) return emptyList()

        val segments = mutableListOf<RoleSegment>()
        val buffer = StringBuilder()
        var inQuote = false
        var lastNarration = "" // 进入对话时回看，用于抓说话人

        fun pushNarration() {
            val s = buffer.toString().trim()
            buffer.clear()
            if (s.isNotEmpty()) segments += RoleSegment(SpeechRole.NARRATION, null, s)
        }

        fun pushDialogue() {
            val s = buffer.toString().trim()
            buffer.clear()
            if (s.isNotEmpty()) segments += RoleSegment(SpeechRole.DIALOGUE, extractSpeaker(lastNarration), s)
        }

        for (ch in normalized) {
            when {
                !inQuote && ch in OPEN_QUOTES -> {
                    lastNarration = buffer.toString()
                    pushNarration()
                    inQuote = true
                    buffer.append(ch) // 保留引号，朗读时保留对话语气（与原单音色路径一致）
                }
                inQuote && ch in CLOSE_QUOTES -> {
                    buffer.append(ch)
                    pushDialogue()
                    inQuote = false
                }
                else -> buffer.append(ch)
            }
        }
        // 收尾：未闭合的引号按对话处理，避免末段对话被当旁白。
        if (buffer.toString().trim().isNotEmpty()) {
            if (inQuote) pushDialogue() else pushNarration()
        }
        return segments
    }

    /** 从对话前的旁白尾部抽取说话人名；取最后一行的尾部以缩小匹配范围、降低误报。 */
    private fun extractSpeaker(narration: String): String? {
        if (narration.isBlank()) return null
        val tail = narration.lineSequence().lastOrNull()?.takeLast(48) ?: return null
        return SPEAKER_PATTERN.find(tail)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** 给定片段与角色档，解析应使用的音色名；未配置则回落到 [fallback]。 */
    fun voiceFor(
        segment: RoleSegment,
        narrationVoice: String?,
        dialogueVoice: String?,
        characterVoices: Map<String, String>,
        fallback: String
    ): String = voiceFor(segment.role, segment.character, narrationVoice, dialogueVoice, characterVoices, fallback)

    /** 直接以角色 + 说话人解析音色，避免每片段构造 [RoleSegment]。 */
    fun voiceFor(
        role: SpeechRole,
        character: String?,
        narrationVoice: String?,
        dialogueVoice: String?,
        characterVoices: Map<String, String>,
        fallback: String
    ): String = when (role) {
        SpeechRole.NARRATION -> narrationVoice ?: fallback
        SpeechRole.DIALOGUE -> character?.let { characterVoices[it] } ?: dialogueVoice ?: fallback
    }
}
