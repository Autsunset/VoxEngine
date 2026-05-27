package com.voxengine.engine

interface TTSEngine {
    val id: String
    val name: String
    val description: String
    val supportsVoiceClone: Boolean
    val supportsVoiceDesign: Boolean

    suspend fun synthesize(
        text: String,
        voice: String,
        style: String? = null,
        optimizeTextPreview: Boolean = false
    ): SynthesisResult

    suspend fun getVoices(): List<VoiceInfo>
    suspend fun getStyles(): List<String>
    fun isConfigured(): Boolean
    suspend fun cloneVoice(name: String, referenceAudio: ByteArray): VoiceInfo
    suspend fun designVoice(description: String): VoiceInfo
}
