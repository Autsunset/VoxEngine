package com.voxengine.engine.mimo.model

import com.google.gson.annotations.SerializedName

/**
 * MiMo TTS OpenAI 兼容接口的 Gson 请求/响应模型。
 * 独立子包，便于 ProGuard 用一条 `-keep` 覆盖全部反射模型，
 * 同时让引擎逻辑代码（MiMoEngine / MiMoTTSClient）仍受 R8 优化。
 */
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
