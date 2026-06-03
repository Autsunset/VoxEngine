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
import com.voxengine.util.LogManager
import com.voxengine.util.SpeechTextNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
        val baseUrl = settingsRepository.baseUrl.first()
        val apiKey = settingsRepository.apiKey.first()
        val ua = settingsRepository.userAgent.first()
        if (apiKey.isBlank()) throw IllegalStateException("MiMo API Key 未配置")

        val existing = client
        if (existing != null) {
            existing.updateConfig(baseUrl, apiKey, ua)
            return existing
        }

        val newClient = MiMoTTSClient(baseUrl, apiKey, ua)
        client = newClient
        return newClient
    }

    fun updateClientConfig(baseUrl: String, apiKey: String, userAgent: String? = null) {
        client?.updateConfig(baseUrl, apiKey, userAgent)
    }

    private data class ResolvedVoice(val model: String, val voiceParam: String)

    /** 根据音色名解析出请求用的模型与 voice 参数（自定义音色查库，否则按预设处理）。 */
    private suspend fun resolveVoice(voice: String): ResolvedVoice {
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val customVoice = db.voiceDao().getVoiceByName(voice)
        return when (customVoice?.type) {
            "clone" -> ResolvedVoice(MiMoTTSClient.MODEL_CLONE, customVoice.voiceParam)
            "design" -> ResolvedVoice(MiMoTTSClient.MODEL_DESIGN, customVoice.voiceParam)
            else -> ResolvedVoice(MiMoTTSClient.MODEL_PRESET, voice)
        }
    }

    private fun splitTextToSentences(text: String): List<String> {
        // 按中文句末标点分段，保留引号和内容在一起
        val normalizedText = SpeechTextNormalizer.normalize(text)
        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in normalizedText) {
            sb.append(ch)
            if (ch in charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n')) {
                val s = sb.toString().trim()
                // 跳过只有引号等符号的空段
                if (s.isNotEmpty() && s.any { it.isLetterOrDigit() }) {
                    sentences.add(s)
                }
                sb.clear()
            }
        }
        val remaining = sb.toString().trim()
        // 剩余部分也要检查是否有实际内容
        if (remaining.isNotEmpty() && remaining.any { it.isLetterOrDigit() }) {
            sentences.add(remaining)
        }
        return if (sentences.isEmpty()) listOf(normalizedText) else sentences
    }

    /**
     * 流式合成：分句后用有界并发预取，按原始顺序就绪即回调该句 PCM。
     * 首字延迟≈单句延迟，而非整段。供系统 TTS 路径边合成边播放。
     * @param concurrency 同时在途的请求数上限（1-8）。
     * @param onPcm 每句就绪时回调其 PCM（已从 WAV 抽取），按句子原始顺序。
     */
    suspend fun synthesizeStreaming(
        text: String,
        voice: String,
        style: String?,
        concurrency: Int,
        onPcm: suspend (ByteArray) -> Unit
    ) {
        val c = getClient()
        val resolved = resolveVoice(voice)
        val sentences = splitTextToSentences(text)
        val limit = concurrency.coerceIn(1, 8)
        Log.d(TAG, "Streaming synthesis: ${sentences.size} segments, concurrency=$limit")
        LogManager.appendLog("D", TAG, "Streaming synthesis: ${sentences.size} segments, concurrency=$limit")

        coroutineScope {
            val semaphore = Semaphore(limit)
            // 全部立即排队，由 semaphore 控制实际在途数；async 让后续句子在当前句播放时已在合成。
            val jobs = sentences.map { sentence ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        com.voxengine.util.RetryPolicy.withRetry(
                            retryCount = 3,
                            baseDelayMs = 1500,
                            onRetry = { attempt, error ->
                                LogManager.appendLog("W", TAG, "Streaming segment retry $attempt: ${error.message}")
                            },
                            block = { c.synthesize(text = sentence, voice = resolved.voiceParam, model = resolved.model, style = style) }
                        )
                    }
                }
            }
            // 按原始顺序消费：第 i 句就绪立即出声，i+1… 仍在后台合成。
            for ((index, job) in jobs.withIndex()) {
                val result = try {
                    job.await()
                } catch (e: CancellationException) {
                    jobs.forEach { it.cancel() }
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming segment $index failed: ${e.message}")
                    LogManager.appendLog("E", TAG, "Streaming segment $index failed: ${e.message}")
                    jobs.forEach { it.cancel() }
                    throw e
                }
                onPcm(AudioUtils.extractPcmData(result.audioData))
            }
        }
    }

    override suspend fun synthesize(
        text: String,
        voice: String,
        style: String?,
        optimizeTextPreview: Boolean
    ): SynthesisResult {
        val speechText = SpeechTextNormalizer.normalize(text)
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val customVoice = db.voiceDao().getVoiceByName(voice)
        val voiceFingerprint = customVoice?.let { custom ->
            "${custom.engineId}:${custom.type}:${custom.model}:${custom.voiceParam.hashCode()}:${custom.createdAt}"
        } ?: "preset:$voice"
        val cacheKey = AudioCache.generateKey(
            text = speechText,
            voice = voice,
            style = style,
            engineId = id,
            voiceFingerprint = voiceFingerprint
        )
        if (!optimizeTextPreview) {
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
        }

        val c = getClient()

        val mimoResult = if (customVoice != null) {
            when (customVoice.type) {
                "clone" -> {
                    c.synthesize(
                        text = speechText,
                        voice = customVoice.voiceParam,
                        model = MiMoTTSClient.MODEL_CLONE,
                        style = style
                    )
                }
                "design" -> {
                    c.synthesize(
                        text = speechText,
                        voice = customVoice.voiceParam,
                        model = MiMoTTSClient.MODEL_DESIGN,
                        style = style,
                        optimizeTextPreview = optimizeTextPreview
                    )
                }
                else -> {
                    c.synthesize(speechText, voice, MiMoTTSClient.MODEL_PRESET, style)
                }
            }
        } else {
            c.synthesize(speechText, voice, MiMoTTSClient.MODEL_PRESET, style)
        }

        // 存入缓存
        if (!optimizeTextPreview) {
            AudioCache.put(cacheKey, mimoResult.audioData)
        }

        return SynthesisResult(
            audioData = mimoResult.audioData,
            format = AudioFormat.WAV,
            sampleRate = AudioUtils.getWavSampleRate(mimoResult.audioData),
            elapsedMs = mimoResult.elapsedMs
        )
    }

    override suspend fun getVoices(): List<EngineVoiceInfo> {
        val presetVoices = MiMoTTSClient.PRESET_VOICES.map {
            EngineVoiceInfo(
                id = it.id,
                name = it.name,
                description = it.description,
                type = VoiceType.PRESET,
                engineId = id
            )
        }

        // 加载自定义音色（轻量查询，不含 voiceParam/audioBase64）
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val customVoices = db.voiceDao().getAllVoiceItems().first().map { item ->
            val type = when (item.type) {
                "clone" -> VoiceType.CLONE
                "design" -> VoiceType.DESIGN
                else -> VoiceType.PRESET
            }
            EngineVoiceInfo(
                id = item.name,
                name = item.name,
                description = item.description.ifEmpty { if (type == VoiceType.CLONE) "克隆音色" else "设计音色" },
                type = type,
                engineId = id
            )
        }

        return presetVoices + customVoices
    }

    override suspend fun getStyles(): List<String> {
        return listOf(
            "无",
            // 基础情绪
            "开心", "悲伤", "生气", "恐惧", "惊讶", "兴奋", "委屈", "平静", "冷漠",
            // 复合情绪
            "怅然", "欣慰", "无奈", "愧疚", "释然", "动情",
            // 整体语调
            "温柔", "高冷", "活泼", "严肃", "慵懒", "俏皮", "深沉", "干练",
            // 音色定位
            "磁性", "醇厚", "清亮", "空灵", "甜美", "沙哑",
            // 人设腔调
            "夹子音", "御姐音", "正太音", "大叔音", "台湾腔",
            // 方言
            "粤语", "四川话",
            // 其他
            "悄悄话", "唱歌"
        )
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
