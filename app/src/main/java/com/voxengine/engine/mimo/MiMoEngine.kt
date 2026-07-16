package com.voxengine.engine.mimo

import android.util.Base64
import android.util.Log
import com.voxengine.audio.AudioUtils
import com.voxengine.data.AppDatabase
import com.voxengine.data.SettingsRepository
import com.voxengine.data.VoiceEntity
import com.voxengine.engine.AudioCache
import com.voxengine.engine.AudioFormat
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class MiMoEngine(
    private val settingsRepository: SettingsRepository
) : TTSEngine {

    override val id = "mimo"
    override val name = "MiMo TTS"
    override val description = "小米 MiMo 语音合成引擎"
    override val supportsVoiceClone = true
    override val supportsVoiceDesign = true

    @Volatile private var client: MiMoTTSClient? = null
    private val clientConfigMutex = Mutex()

    // 音色解析缓存：避免每段合成都 SELECT * 拉出含大 base64 的 voiceParam。
    // 增删改音色时 invalidateVoiceCache；fingerprint 含 createdAt/hash，重克隆不会串缓存。
    private data class ResolvedVoice(
        val model: String,
        val voiceParam: String,
        val voiceFingerprint: String
    )
    private val voiceResolveCache = ConcurrentHashMap<String, ResolvedVoice>()

    private suspend fun getClient(): MiMoTTSClient {
        client?.let { return it }
        val config = settingsRepository.getMiMoClientConfig()
        if (config.apiKey.isBlank()) throw IllegalStateException("MiMo API Key 未配置")

        clientConfigMutex.withLock {
            val existing = client
            if (existing != null) return existing
            val newClient = MiMoTTSClient()
            newClient.updateConfig(config.baseUrl, config.apiKey, config.userAgent)
            client = newClient
            return newClient
        }
    }

    fun updateClientConfig(baseUrl: String, apiKey: String, userAgent: String? = null) {
        if (apiKey.isBlank()) {
            client = null
            return
        }
        client?.updateConfig(baseUrl, apiKey, userAgent)
    }

    /** 音色变更后失效缓存，避免旧 voiceParam / fingerprint 继续使用。 */
    fun invalidateVoiceCache(voiceName: String? = null) {
        if (voiceName == null) voiceResolveCache.clear()
        else voiceResolveCache.remove(voiceName)
    }

    /** 根据音色名解析出请求用的模型、voice 参数与缓存指纹（自定义音色查库，否则按预设处理）。 */
    private suspend fun resolveVoice(voice: String): ResolvedVoice {
        voiceResolveCache[voice]?.let { return it }
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val customVoice = db.voiceDao().getVoiceByEngineAndName(id, voice)
        val resolved = when (customVoice?.type) {
            "clone" -> ResolvedVoice(
                model = MiMoTTSClient.MODEL_CLONE,
                voiceParam = customVoice.voiceParam,
                voiceFingerprint = "${customVoice.engineId}:${customVoice.type}:${customVoice.model}:${customVoice.voiceParam.hashCode()}:${customVoice.createdAt}"
            )
            "design" -> ResolvedVoice(
                model = MiMoTTSClient.MODEL_DESIGN,
                voiceParam = customVoice.voiceParam,
                voiceFingerprint = "${customVoice.engineId}:${customVoice.type}:${customVoice.model}:${customVoice.voiceParam.hashCode()}:${customVoice.createdAt}"
            )
            else -> ResolvedVoice(
                model = MiMoTTSClient.MODEL_PRESET,
                voiceParam = voice,
                voiceFingerprint = "preset:$voice"
            )
        }
        voiceResolveCache[voice] = resolved
        return resolved
    }

    private fun splitTextToSentences(text: String): List<String> =
        SpeechTextNormalizer.splitSentences(text)

    private fun silenceResult(): SynthesisResult {
        val silence = AudioUtils.silentWav()
        return SynthesisResult(
            audioData = silence,
            format = AudioFormat.WAV,
            sampleRate = AudioUtils.getWavSampleRate(silence),
            elapsedMs = 0
        )
    }

    /**
     * 流式合成：分句后用有界并发预取，按原始顺序就绪即回调该句 PCM。
     * 首字延迟≈单句延迟，而非整段。供系统 TTS 路径边合成边播放。
     * 分句级命中 [AudioCache]，避免 Legado 等重复请求同一句时重复计费。
     * @param concurrency 同时在途的请求数上限（1-8）。
     * @param retryCount 可重试错误（429/IOException）的额外重试次数，默认 3。
     * @param retryBaseDelayMs 退避基准；第 n 次重试前延迟 retryBaseDelayMs * n^2，默认 1500ms。
     * @param onPcm 每句就绪时回调其 PCM（已从 WAV 抽取），按句子原始顺序。
     */
    suspend fun synthesizeStreaming(
        text: String,
        voice: String,
        style: String?,
        concurrency: Int,
        retryCount: Int = DEFAULT_STREAMING_RETRY_COUNT,
        retryBaseDelayMs: Long = DEFAULT_STREAMING_RETRY_BASE_DELAY_MS,
        onPcm: suspend (ByteArray) -> Unit
    ) {
        val c = getClient()
        val resolved = resolveVoice(voice)
        val temperature = settingsRepository.defaultTemperature.first()
        val sentences = splitTextToSentences(text)
        val limit = concurrency.coerceIn(1, 8)
        Log.d(TAG, "Streaming synthesis: ${sentences.size} segments, concurrency=$limit")
        LogManager.appendLog("D", TAG, "Streaming synthesis: ${sentences.size} segments, concurrency=$limit")

        coroutineScope {
            val semaphore = Semaphore(limit)
            // 全部立即排队，由 semaphore 控制实际在途数；async 让后续句子在当前句播放时已在合成。
            val jobs = sentences.map { sentence ->
                async(Dispatchers.IO) {
                    if (!SpeechTextNormalizer.hasSpeakableContent(sentence)) {
                        return@async silenceResult()
                    }
                    val speechText = SpeechTextNormalizer.normalize(sentence)
                    val cacheKey = AudioCache.generateKey(
                        text = speechText,
                        voice = voice,
                        style = style,
                        engineId = id,
                        voiceFingerprint = resolved.voiceFingerprint,
                        temperature = temperature
                    )
                    AudioCache.get(cacheKey)?.let { cached ->
                        return@async SynthesisResult(
                            audioData = cached,
                            format = AudioFormat.WAV,
                            sampleRate = AudioUtils.getWavSampleRate(cached),
                            elapsedMs = 0
                        )
                    }
                    semaphore.withPermit {
                        val mimoResult = com.voxengine.util.RetryPolicy.withRetry(
                            retryCount = retryCount,
                            baseDelayMs = retryBaseDelayMs,
                            onRetry = { attempt, error ->
                                LogManager.appendLog("W", TAG, "Streaming segment retry $attempt: ${error.message}")
                            },
                            block = {
                                c.synthesize(
                                    text = speechText,
                                    voice = resolved.voiceParam,
                                    model = resolved.model,
                                    style = style,
                                    temperature = temperature
                                )
                            }
                        )
                        AudioCache.put(cacheKey, mimoResult.audioData)
                        SynthesisResult(
                            audioData = mimoResult.audioData,
                            format = AudioFormat.WAV,
                            sampleRate = AudioUtils.getWavSampleRate(mimoResult.audioData),
                            elapsedMs = mimoResult.elapsedMs
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
        if (!optimizeTextPreview && !SpeechTextNormalizer.hasSpeakableContent(speechText)) {
            return silenceResult()
        }
        val resolved = resolveVoice(voice)
        val temperature = settingsRepository.defaultTemperature.first()
        val cacheKey = AudioCache.generateKey(
            text = speechText,
            voice = voice,
            style = style,
            engineId = id,
            voiceFingerprint = resolved.voiceFingerprint,
            temperature = temperature
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
        val mimoResult = c.synthesize(
            text = speechText,
            voice = resolved.voiceParam,
            model = resolved.model,
            style = style,
            optimizeTextPreview = optimizeTextPreview && resolved.model == MiMoTTSClient.MODEL_DESIGN,
            temperature = temperature
        )

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
            val meta = MiMoTTSClient.PRESET_VOICE_META[it.name]
            EngineVoiceInfo(
                id = it.id,
                name = it.name,
                description = it.description,
                type = VoiceType.PRESET,
                engineId = id,
                gender = meta?.first,
                ageGroup = meta?.second
            )
        }

        // 加载自定义音色（轻量查询，不含 voiceParam/audioBase64）
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val customVoices = db.voiceDao().getVoiceItemsByEngine(id).first().map { item ->
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
                engineId = id,
                gender = item.gender,
                ageGroup = item.ageGroup,
                tags = com.voxengine.engine.VoiceTags.parse(item.tags)
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

    /**
     * 内部 runBlocking 读 DataStore——只应在后台/binder 线程调用（如系统 TTS 服务），
     * 勿在主线程或 Compose 组合期同步调用；UI 侧请异步求值（见 TestScreen / ReaderViewModel 做法）。
     */
    override fun isConfigured(): Boolean {
        return runCatching {
            kotlinx.coroutines.runBlocking {
                settingsRepository.apiKey.first().isNotBlank()
            }
        }.getOrDefault(false)
    }

    override suspend fun cloneVoice(name: String, referenceAudio: ByteArray): EngineVoiceInfo {
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
        invalidateVoiceCache(name)

        return EngineVoiceInfo(
            id = "clone_$name",
            name = name,
            description = "克隆音色",
            type = VoiceType.CLONE,
            engineId = id
        )
    }

    override suspend fun designVoice(description: String): EngineVoiceInfo {
        Log.d(TAG, "Designing voice: $description")

        // 保存到数据库
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val name = description.take(20)
        val voiceEntity = VoiceEntity(
            name = name,
            type = "design",
            model = MiMoTTSClient.MODEL_DESIGN,
            voiceParam = description,
            description = description,
            engineId = id
        )
        db.voiceDao().insert(voiceEntity)
        invalidateVoiceCache(name)

        return EngineVoiceInfo(
            id = "design_${description.hashCode()}",
            name = name,
            description = description,
            type = VoiceType.DESIGN,
            engineId = id
        )
    }

    companion object {
        private const val TAG = "MiMoEngine"
        private const val DEFAULT_STREAMING_RETRY_COUNT = 3
        private const val DEFAULT_STREAMING_RETRY_BASE_DELAY_MS = 1500L
    }
}
