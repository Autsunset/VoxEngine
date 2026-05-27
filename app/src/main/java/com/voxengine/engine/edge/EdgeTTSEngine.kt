package com.voxengine.engine.edge

import com.voxengine.data.SettingsRepository
import com.voxengine.engine.TTSEngine
import com.voxengine.engine.VoiceInfo
import com.voxengine.engine.SynthesisResult
import com.voxengine.engine.VoiceType

class EdgeTTSEngine(
    private val settingsRepository: SettingsRepository
) : TTSEngine {

    override val id = "edge"
    override val name = "Edge TTS"
    override val description = "微软 Edge TTS 引擎（开发中）"
    override val supportsVoiceClone = false
    override val supportsVoiceDesign = false

    override suspend fun synthesize(
        text: String,
        voice: String,
        style: String?,
        optimizeTextPreview: Boolean
    ): SynthesisResult {
        throw NotImplementedError("Edge TTS 引擎开发中")
    }

    override suspend fun getVoices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("zh-CN-XiaoxiaoNeural", "晓晓", "中文女声", VoiceType.PRESET, id),
            VoiceInfo("zh-CN-YunxiNeural", "云希", "中文男声", VoiceType.PRESET, id),
            VoiceInfo("zh-CN-XiaoyiNeural", "晓依", "中文女声", VoiceType.PRESET, id),
            VoiceInfo("en-US-JennyNeural", "Jenny", "英文女声", VoiceType.PRESET, id),
            VoiceInfo("en-US-GuyNeural", "Guy", "英文男声", VoiceType.PRESET, id)
        )
    }

    override suspend fun getStyles(): List<String> = emptyList()

    override fun isConfigured(): Boolean = false

    override suspend fun cloneVoice(name: String, referenceAudio: ByteArray): VoiceInfo {
        throw NotImplementedError("Edge TTS 引擎开发中")
    }

    override suspend fun designVoice(description: String): VoiceInfo {
        throw NotImplementedError("Edge TTS 引擎开发中")
    }
}
