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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private fun splitTextToSentences(text: String): List<String> {
        // 按中文句末标点分段，保留引号和内容在一起
        val sentences = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
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
        return if (sentences.isEmpty()) listOf(text) else sentences
    }

    suspend fun synthesizeParallel(
        text: String,
        voice: String,
        style: String?
    ): SynthesisResult {
        val startTime = System.currentTimeMillis()
        val c = getClient()

        // 检查自定义音色
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val customVoice = db.voiceDao().getVoiceByName(voice)

        val model: String
        val voiceParam: String
        when {
            customVoice != null && customVoice.type == "clone" -> {
                model = MiMoTTSClient.MODEL_CLONE
                voiceParam = customVoice.voiceParam
            }
            customVoice != null && customVoice.type == "design" -> {
                model = MiMoTTSClient.MODEL_DESIGN
                voiceParam = customVoice.voiceParam
            }
            else -> {
                model = MiMoTTSClient.MODEL_PRESET
                voiceParam = voice
            }
        }

        val sentences = splitTextToSentences(text)
        Log.d(TAG, "Parallel synthesis: ${sentences.size} segments from ${text.length} chars")
        LogManager.appendLog("D", TAG, "Parallel synthesis: ${sentences.size} segments from ${text.length} chars")

        // 并行请求每个句子，保留索引以便正确排序
        val indexedResults = coroutineScope {
            sentences.mapIndexed { index, sentence ->
                async(Dispatchers.IO) {
                    try {
                        val result = c.synthesize(
                            text = sentence,
                            voice = voiceParam,
                            model = model,
                            style = style
                        )
                        Log.d(TAG, "Segment $index done: ${result.audioData.size} bytes")
                        LogManager.appendLog("D", TAG, "Segment $index done: ${result.audioData.size} bytes")
                        IndexedValue(index, result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Segment $index failed: ${e.message}")
                        LogManager.appendLog("E", TAG, "Segment $index failed: ${e.message}")
                        IndexedValue(index, null)
                    }
                }
            }.awaitAll()
        }

        // 按原始索引排序，提取 PCM 数据并拼接
        val sortedResults = indexedResults
            .filter { it.value != null }
            .sortedBy { it.index }
            .map { it.value!! }

        // 提取每个结果的 PCM 数据并拼接
        val pcmChunks = sortedResults.map { result ->
            AudioUtils.extractPcmData(result.audioData)
        }

        if (pcmChunks.isEmpty()) throw Exception("All segments failed")

        val totalPcmSize = pcmChunks.sumOf { it.size }
        val combinedPcm = ByteArray(totalPcmSize)
        var offset = 0
        for (chunk in pcmChunks) {
            System.arraycopy(chunk, 0, combinedPcm, offset, chunk.size)
            offset += chunk.size
        }

        // 包装为 WAV
        val wavData = AudioUtils.pcmToWav(combinedPcm, 24000, 1, 16)
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Parallel synthesis done: ${wavData.size} bytes in ${elapsed}ms")
        LogManager.appendLog("D", TAG, "Parallel synthesis done: ${wavData.size} bytes in ${elapsed}ms")

        return SynthesisResult(
            audioData = wavData,
            format = AudioFormat.WAV,
            sampleRate = 24000,
            elapsedMs = elapsed
        )
    }

    override suspend fun synthesize(
        text: String,
        voice: String,
        style: String?,
        optimizeTextPreview: Boolean
    ): SynthesisResult {
        // 检查缓存（仅非 optimizeTextPreview 模式缓存）
        val cacheKey = AudioCache.generateKey(text, voice, style)
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

        // 检查是否是自定义音色（clone 或 design）
        val db = AppDatabase.getDatabase(com.voxengine.VoxEngineApplication.instance)
        val customVoice = db.voiceDao().getVoiceByName(voice)

        val mimoResult = if (customVoice != null) {
            when (customVoice.type) {
                "clone" -> {
                    c.synthesize(
                        text = text,
                        voice = customVoice.voiceParam,
                        model = MiMoTTSClient.MODEL_CLONE,
                        style = style
                    )
                }
                "design" -> {
                    c.synthesize(
                        text = text,
                        voice = customVoice.voiceParam,
                        model = MiMoTTSClient.MODEL_DESIGN,
                        style = style,
                        optimizeTextPreview = optimizeTextPreview
                    )
                }
                else -> {
                    c.synthesize(text, voice, MiMoTTSClient.MODEL_PRESET, style)
                }
            }
        } else {
            c.synthesize(text, voice, MiMoTTSClient.MODEL_PRESET, style)
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
