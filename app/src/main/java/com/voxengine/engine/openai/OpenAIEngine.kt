package com.voxengine.engine.openai

import com.voxengine.data.SettingsRepository
import com.voxengine.engine.TTSEngine
import com.voxengine.engine.VoiceInfo
import com.voxengine.engine.SynthesisResult
import com.voxengine.engine.VoiceType

class OpenAIEngine(
    private val settingsRepository: SettingsRepository
) : TTSEngine {

    override val id = "openai"
    override val name = "OpenAI TTS"
    override val description = "OpenAI 语音合成引擎（开发中）"
    override val supportsVoiceClone = false
    override val supportsVoiceDesign = false

    override suspend fun synthesize(
        text: String,
        voice: String,
        style: String?
    ): SynthesisResult {
        throw NotImplementedError("OpenAI TTS 引擎开发中")
    }

    override suspend fun getVoices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("alloy", "Alloy", "中性声音", VoiceType.PRESET, id),
            VoiceInfo("echo", "Echo", "男声", VoiceType.PRESET, id),
            VoiceInfo("fable", "Fable", "英式口音", VoiceType.PRESET, id),
            VoiceInfo("onyx", "Onyx", "深沉男声", VoiceType.PRESET, id),
            VoiceInfo("nova", "Nova", "女声", VoiceType.PRESET, id),
            VoiceInfo("shimmer", "Shimmer", "温柔女声", VoiceType.PRESET, id)
        )
    }

    override suspend fun getStyles(): List<String> = emptyList()

    override fun isConfigured(): Boolean = false

    override suspend fun cloneVoice(name: String, referenceAudio: ByteArray): VoiceInfo {
        throw NotImplementedError("OpenAI TTS 引擎开发中")
    }

    override suspend fun designVoice(description: String): VoiceInfo {
        throw NotImplementedError("OpenAI TTS 引擎开发中")
    }
}
