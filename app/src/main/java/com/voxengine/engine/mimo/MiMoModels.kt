package com.voxengine.engine.mimo

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.voxengine.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class MiMoTTSClient(
    private var baseUrl: String = "https://api.xiaomimimo.com",
    private var apiKey: String = "",
    private var userAgent: String = "openclaw/unknown"
) {
    private val gson = Gson()
    private var client = buildClient(userAgent)

    private fun buildClient(ua: String) = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", ua)
                .build()
            chain.proceed(request)
        }
        .build()

    fun updateConfig(baseUrl: String, apiKey: String, userAgent: String? = null) {
        this.baseUrl = baseUrl.trimEnd('/')
        this.apiKey = apiKey
        if (userAgent != null && userAgent != this.userAgent) {
            this.userAgent = userAgent
            this.client = buildClient(userAgent)
        }
    }

    suspend fun synthesize(
        text: String,
        voice: String,
        model: String = MODEL_PRESET,
        style: String? = null,
        optimizeTextPreview: Boolean = false
    ): SynthesisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        // 风格标签放在 assistant 内容开头，格式：(风格)
        val content = if (style != null && style != "无") {
            "($style)$text"
        } else {
            text
        }

        // 根据模型类型构建不同的请求体
        val (userContent, assistantContent, audioConfig) = when (model) {
            MODEL_DESIGN -> {
                if (optimizeTextPreview) {
                    // optimizeTextPreview 模式：只需 user 描述，无需 assistant 文本
                    Triple(voice, null, AudioConfig(format = "wav", optimizeTextPreview = true))
                } else {
                    Triple(voice, content, AudioConfig(format = "wav"))
                }
            }
            MODEL_CLONE -> {
                Pair("", AudioConfig(format = "wav", voice = voice)).let {
                    Triple(it.first, content, it.second)
                }
            }
            else -> {
                Pair("", AudioConfig(format = "wav", voice = voice)).let {
                    Triple(it.first, content, it.second)
                }
            }
        }

        val messages = mutableListOf(
            Message(role = "user", content = userContent)
        )
        if (assistantContent != null) {
            messages.add(Message(role = "assistant", content = assistantContent))
        }

        val request = TTSRequest(
            model = model,
            messages = messages,
            audio = audioConfig
        )

        val json = gson.toJson(request)
        Log.d(TAG, "Request model=$model voice=$voice json=$json")
        LogManager.appendLog("D", TAG, "Request model=$model voice=$voice json=$json")

        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("API error ${response.code}: $body")
        }

        val ttsResponse = gson.fromJson(body, TTSResponse::class.java)
        val audioBase64 = ttsResponse.choices.firstOrNull()?.message?.audio?.data
            ?: throw Exception("No audio data in response")

        val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
        val elapsed = System.currentTimeMillis() - startTime

        SynthesisResult(
            audioData = audioBytes,
            format = "wav",
            elapsedMs = elapsed
        )
    }

    data class SynthesisResult(
        val audioData: ByteArray,
        val format: String,
        val elapsedMs: Long
    )

    data class VoiceInfo(
        val id: String,
        val name: String,
        val description: String,
        val model: String
    )

    companion object {
        private const val TAG = "MiMoTTSClient"
        const val MODEL_PRESET = "mimo-v2.5-tts"
        const val MODEL_CLONE = "mimo-v2.5-tts-voiceclone"
        const val MODEL_DESIGN = "mimo-v2.5-tts-voicedesign"

        val PRESET_VOICES = listOf(
            VoiceInfo("冰糖", "冰糖", "甜美可爱女声", MODEL_PRESET),
            VoiceInfo("茉莉", "茉莉", "温柔知性女声", MODEL_PRESET),
            VoiceInfo("苏打", "苏打", "活力阳光男声", MODEL_PRESET),
            VoiceInfo("白桦", "白桦", "沉稳磁性男声", MODEL_PRESET),
            VoiceInfo("Mia", "Mia", "英文女声", MODEL_PRESET),
            VoiceInfo("Chloe", "Chloe", "英文女声", MODEL_PRESET),
            VoiceInfo("Milo", "Milo", "英文男声", MODEL_PRESET),
            VoiceInfo("Dean", "Dean", "英文男声", MODEL_PRESET)
        )
    }
}

data class TTSRequest(
    val model: String,
    val stream: Boolean = false,
    val messages: List<Message>,
    val audio: AudioConfig
)

data class Message(
    val role: String,
    val content: String
)

data class AudioConfig(
    val format: String = "wav",
    val voice: String? = null,
    @SerializedName("optimize_text_preview")
    val optimizeTextPreview: Boolean? = null
)

data class TTSResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: ResponseMessage
)

data class ResponseMessage(
    val audio: AudioData?
)

data class AudioData(
    val data: String
)
