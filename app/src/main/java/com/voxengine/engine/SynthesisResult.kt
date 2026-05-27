package com.voxengine.engine

data class SynthesisResult(
    val audioData: ByteArray,
    val format: AudioFormat = AudioFormat.WAV,
    val sampleRate: Int = 24000,
    val elapsedMs: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesisResult) return false
        return audioData.contentEquals(other.audioData) &&
                format == other.format &&
                sampleRate == other.sampleRate &&
                elapsedMs == other.elapsedMs
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + elapsedMs.hashCode()
        return result
    }
}
