package com.voxengine.engine.mimo

import android.util.Base64
import android.util.Log
import com.voxengine.audio.AudioUtils
import com.voxengine.data.AppDatabase
import com.voxengine.data.SettingsRepository
import com.voxengine.data.VoiceEntity
import com.voxengine.engine.AudioCache
import com.voxengine.engine.AudioFormat
import com.voxengine.engine.EngineRegistry
import com.voxengine.engine.TTSEngine
import com.voxengine.engine.VoiceInfo as EngineVoiceInfo
import com.voxengine.engine.SynthesisResult
import com.voxengine.engine.VoiceType
import kotlinx.coroutines.flow.first

class MiMoEngine(
    private val settingsRepository: SettingsRepository
) : TTSEngine {

    override val id = "mimo"
    override val name = "MiMo TTS"
    override val description = "小米 MiMo 语音合成引擎"
    override val supportsVoiceClone = true
    override val supportsVoiceDesign = true

    private var client: MiMoTTSClient? = null

    private suspend fun getClient(): MiMoTTSClient {
        val existing = client
        if (existing != null) return existing

        val baseUrl = settingsRepository.baseUrl.first()
        val apiKey = settingsRepository.apiKey.first()
        if (apiKey.isBlank()) throw IllegalStateException("MiMo API Key 未配置")

        val newClient = MiMoTTSClient(baseUrl, apiKey)
        client = newClient
        return newClient
    }

    fun updateClientConfig(baseUrl: String, apiKey: String) {
        client?.updateConfig(baseUrl, apiKey)
    }

    override suspend fun synthesize(
        text: String,
        voice: String,
        style: String?,
        speed: Float
    ): SynthesisResult {
        // 检查缓存
        val cacheKey = AudioCache.generateKey(text, voice, style, speed)
        val cachedAudio = AudioCache.get(cacheKey)
        if (cachedAudio != null) {
            Log.d(TAG, "Cache hit for key: $cacheKey")
            return SynthesisResult(
                audioData = cachedAudio,
                format = AudioFormat.WAV,
                sampleRate = AudioUtils.getWavSampleRate(cachedAudio),
                elapsedMs = 0
            )
        }

        val c = getClient()
        
        // 检查是否是自定义音色（clone 或 design）
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val customVoice = db.voiceDao().getAllVoices().first().find { it.name == voice }
        
        val mimoResult = if (customVoice != null) {
            // 使用自定义音色
            when (customVoice.type) {
                "clone" -> {
                    // 克隆音色使用 voiceclone 模型
                    c.synthesize(
                        text = text,
                        voice = customVoice.voiceParam,
                        model = MiMoTTSClient.MODEL_CLONE,
                        style = style,
                        speed = speed
                    )
                }
                "design" -> {
                    // 设计音色使用 voicedesign 模型
                    c.synthesize(
                        text = text,
                        voice = customVoice.voiceParam,
                        model = MiMoTTSClient.MODEL_DESIGN,
                        style = style,
                        speed = speed
                    )
                }
                else -> {
                    // 预设音色
                    c.synthesize(text, voice, MiMoTTSClient.MODEL_PRESET, style, speed)
                }
            }
        } else {
            // 预设音色
            c.synthesize(text, voice, MiMoTTSClient.MODEL_PRESET, style, speed)
        }
        
        // 存入缓存
        AudioCache.put(cacheKey, mimoResult.audioData)
        
        return SynthesisResult(
            audioData = mimoResult.audioData,
            format = AudioFormat.WAV,
            sampleRate = AudioUtils.getWavSampleRate(mimoResult.audioData),
            elapsedMs = mimoResult.elapsedMs
        )
    }

    override suspend fun getVoices(): List<EngineVoiceInfo> {
        return MiMoTTSClient.PRESET_VOICES.map {
            EngineVoiceInfo(
                id = it.id,
                name = it.name,
                description = it.description,
                type = VoiceType.PRESET,
                engineId = id
            )
        }
    }

    override suspend fun getStyles(): List<String> {
        return listOf("无", "开心", "悲伤", "生气", "东北话", "粤语", "四川话", "悄悄话", "唱歌")
    }

    override fun isConfigured(): Boolean {
        return runCatching {
            kotlinx.coroutines.runBlocking {
                settingsRepository.apiKey.first().isNotBlank()
            }
        }.getOrDefault(false)
    }

    override suspend fun cloneVoice(name: String, referenceAudio: ByteArray): EngineVoiceInfo {
        val c = getClient()
        
        // 上传参考音频进行克隆
        val audioBase64 = Base64.encodeToString(referenceAudio, Base64.NO_WRAP)
        val voiceParam = "data:audio/mpeg;base64,$audioBase64"
        
        Log.d(TAG, "Cloning voice: $name, audio size: ${referenceAudio.size} bytes")
        
        // 保存到数据库
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val voiceEntity = VoiceEntity(
            name = name,
            type = "clone",
            model = MiMoTTSClient.MODEL_CLONE,
            voiceParam = voiceParam,
            description = "克隆音色",
            engineId = id
        )
        db.voiceDao().insert(voiceEntity)
        
        return EngineVoiceInfo(
            id = "clone_$name",
            name = name,
            description = "克隆音色",
            type = VoiceType.CLONE,
            engineId = id
        )
    }

    override suspend fun designVoice(description: String): EngineVoiceInfo {
        val c = getClient()
        
        Log.d(TAG, "Designing voice: $description")
        
        // 保存到数据库
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val voiceEntity = VoiceEntity(
            name = description.take(20),
            type = "design",
            model = MiMoTTSClient.MODEL_DESIGN,
            voiceParam = description,
            description = description,
            engineId = id
        )
        db.voiceDao().insert(voiceEntity)
        
        return EngineVoiceInfo(
            id = "design_${description.hashCode()}",
            name = description.take(20),
            description = description,
            type = VoiceType.DESIGN,
            engineId = id
        )
    }

    companion object {
        private const val TAG = "MiMoEngine"
    }
}
