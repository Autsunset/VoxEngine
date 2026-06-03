package com.voxengine.engine.edge

import com.voxengine.audio.AudioUtils
import com.voxengine.data.SettingsRepository
import com.voxengine.engine.AudioCache
import com.voxengine.engine.AudioFormat
import com.voxengine.engine.TTSEngine
import com.voxengine.engine.VoiceInfo
import com.voxengine.engine.SynthesisResult
import com.voxengine.engine.VoiceType
import com.voxengine.util.SpeechTextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EdgeTTSEngine(
    private val settingsRepository: SettingsRepository
) : TTSEngine {

    override val id = "edge"
    override val name = "Edge TTS"
    override val description = "微软 Edge TTS 引擎（免费）"
    override val supportsVoiceClone = false
    override val supportsVoiceDesign = false

    private val client by lazy { EdgeTTSClient() }

    override suspend fun synthesize(
        text: String,
        voice: String,
        style: String?,
        optimizeTextPreview: Boolean
    ): SynthesisResult = withContext(Dispatchers.IO) {
        val speechText = SpeechTextNormalizer.normalize(text)
        val resolvedVoice = resolveVoiceId(voice)
        val cacheKey = AudioCache.generateKey(
            text = speechText,
            voice = voice,
            style = style,
            engineId = id,
            voiceFingerprint = resolvedVoice
        )
        AudioCache.get(cacheKey)?.let { cached ->
            return@withContext SynthesisResult(
                audioData = cached,
                format = AudioFormat.WAV,
                sampleRate = AudioUtils.getWavSampleRate(cached),
                elapsedMs = 0
            )
        }

        val startTime = System.currentTimeMillis()
        val wav = client.synthesize(speechText, resolvedVoice)
        AudioCache.put(cacheKey, wav)
        SynthesisResult(
            audioData = wav,
            format = AudioFormat.WAV,
            sampleRate = AudioUtils.getWavSampleRate(wav),
            elapsedMs = System.currentTimeMillis() - startTime
        )
    }

    override suspend fun getVoices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("zh-CN-XiaoxiaoNeural", "晓晓", "中文女声", VoiceType.PRESET, id),
            VoiceInfo("zh-CN-YunxiNeural", "云希", "中文男声", VoiceType.PRESET, id),
            VoiceInfo("zh-CN-YunjianNeural", "云健", "中文男声", VoiceType.PRESET, id),
            VoiceInfo("zh-CN-XiaoyiNeural", "晓伊", "中文女声", VoiceType.PRESET, id),
            VoiceInfo("zh-CN-YunyangNeural", "云扬", "中文男声(新闻)", VoiceType.PRESET, id),
            VoiceInfo("zh-CN-liaoning-XiaobeiNeural", "晓北", "东北话女声", VoiceType.PRESET, id),
            VoiceInfo("zh-CN-shaanxi-XiaoniNeural", "晓妮", "陕西话女声", VoiceType.PRESET, id),
            VoiceInfo("zh-HK-HiuMaanNeural", "曉曼", "粤语女声", VoiceType.PRESET, id),
            VoiceInfo("zh-TW-HsiaoChenNeural", "曉臻", "台湾女声", VoiceType.PRESET, id),
            VoiceInfo("en-US-JennyNeural", "Jenny", "英文女声", VoiceType.PRESET, id),
            VoiceInfo("en-US-GuyNeural", "Guy", "英文男声", VoiceType.PRESET, id),
            VoiceInfo("en-US-AriaNeural", "Aria", "英文女声", VoiceType.PRESET, id),
            // 日语音色（MiMo 不支持日语，会把日文汉字按中文读；日语请使用以下 Edge 音色）
            VoiceInfo("ja-JP-NanamiNeural", "七海", "日语女声", VoiceType.PRESET, id),
            VoiceInfo("ja-JP-KeitaNeural", "圭太", "日语男声", VoiceType.PRESET, id),
            VoiceInfo("ja-JP-AoiNeural", "葵", "日语女声", VoiceType.PRESET, id),
            VoiceInfo("ja-JP-DaichiNeural", "大智", "日语男声", VoiceType.PRESET, id),
            VoiceInfo("ja-JP-ShioriNeural", "诗织", "日语女声", VoiceType.PRESET, id),
            VoiceInfo("ja-JP-NaokiNeural", "直树", "日语男声", VoiceType.PRESET, id),
            VoiceInfo("ja-JP-MayuNeural", "真由", "日语女声", VoiceType.PRESET, id)
        )
    }

    override suspend fun getStyles(): List<String> = emptyList()

    /** 设置页存 voice.name、Reader 存 voice.id，统一解析回 Edge 需要的完整 voice id。 */
    private suspend fun resolveVoiceId(voice: String): String {
        if (voice.contains("Neural")) return voice
        val match = getVoices().firstOrNull { it.name == voice || it.id == voice }
        return match?.id ?: "zh-CN-XiaoxiaoNeural"
    }

    override fun isConfigured(): Boolean = true

    override suspend fun cloneVoice(name: String, referenceAudio: ByteArray): VoiceInfo {
        throw NotImplementedError("Edge TTS 不支持音色克隆")
    }

    override suspend fun designVoice(description: String): VoiceInfo {
        throw NotImplementedError("Edge TTS 不支持音色设计")
    }
}
