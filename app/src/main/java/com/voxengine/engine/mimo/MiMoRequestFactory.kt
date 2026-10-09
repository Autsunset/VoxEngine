package com.voxengine.engine.mimo

import com.voxengine.engine.mimo.model.AudioConfig
import com.voxengine.engine.mimo.model.Message
import com.voxengine.engine.mimo.model.TTSRequest

internal object MiMoRequestFactory {
    private val emotionCues = listOf(
        "生气" to Regex("怒道|怒吼|吼道|咆哮|暴喝|厉声|愤怒|恼怒|气急"),
        "恐惧" to Regex("惊恐|恐惧|害怕|畏惧|颤声|颤抖|发抖"),
        "悲伤" to Regex("哭道|哭喊|抽泣|哽咽|悲伤|悲痛|伤心|泪流|落泪|啜泣"),
        "开心" to Regex("笑道|笑着|大笑|轻笑|开心|高兴|欣喜|兴奋|欢呼|喜悦"),
        "惊讶" to Regex("惊讶|震惊|惊呼|失声道|难以置信|怎么可能"),
        "悄悄话" to Regex("低声|轻声|小声|耳语|喃喃|呢喃|悄声|压低声音"),
        "平静" to Regex("平静|淡淡地|淡然|从容|冷静|不紧不慢")
    )

    fun build(
        text: String,
        voice: String,
        model: String = MiMoTTSClient.MODEL_PRESET,
        style: String? = null,
        optimizeTextPreview: Boolean = false,
        temperature: Float? = null,
        context: String? = null
    ): TTSRequest {
        // Never send neighboring prose; exclusion prompts do not prevent audio leakage.
        val styleInstruction = style?.trim()?.takeIf { it.isNotEmpty() && it != "无" }
            ?: context?.takeIf { it.isNotBlank() }?.let {
                val sample = "${it.take(1200)}\n$text"
                emotionCues.firstOrNull { (_, cue) -> cue.containsMatchIn(sample) }?.first
            }
        val (userContent, assistantContent, audioConfig) = when (model) {
            MiMoTTSClient.MODEL_DESIGN -> {
                // Design requests retain only the voice description, including preview mode.
                if (optimizeTextPreview) {
                    Triple(voice, null, AudioConfig(format = "wav", optimizeTextPreview = true))
                } else {
                    Triple(voice, text, AudioConfig(format = "wav"))
                }
            }
            else -> Triple(styleInstruction ?: "", text, AudioConfig(format = "wav", voice = voice))
        }
        val messages = mutableListOf(
            Message(role = "user", content = userContent)
        )
        if (assistantContent != null) {
            messages.add(Message(role = "assistant", content = assistantContent))
        }
        return TTSRequest(
            model = model,
            messages = messages,
            audio = audioConfig,
            temperature = temperature
        )
    }
}
