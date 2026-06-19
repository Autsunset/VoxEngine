package com.voxengine.audio

import android.media.AudioFormat

object AudioUtils {
    fun getWavSampleRate(wavData: ByteArray): Int {
        if (wavData.size < 24) return 24000
        val header = String(wavData, 0, 4)
        return if (header == "RIFF") {
            wavData[24].toInt() and 0xFF or
            (wavData[25].toInt() and 0xFF shl 8) or
            (wavData[26].toInt() and 0xFF shl 16) or
            (wavData[27].toInt() and 0xFF shl 24)
        } else {
            24000
        }
    }

    fun getWavChannelCount(wavData: ByteArray): Int {
        if (wavData.size < 24) return 1
        val header = String(wavData, 0, 4)
        return if (header == "RIFF") {
            wavData[22].toInt() and 0xFF or (wavData[23].toInt() and 0xFF shl 8)
        } else {
            1
        }
    }

    fun getWavBitsPerSample(wavData: ByteArray): Int {
        if (wavData.size < 34) return 16
        val header = String(wavData, 0, 4)
        return if (header == "RIFF") {
            wavData[34].toInt() and 0xFF or (wavData[35].toInt() and 0xFF shl 8)
        } else {
            16
        }
    }

    fun getAudioFormat(bitsPerSample: Int, channelCount: Int): Int {
        return if (bitsPerSample == 16) {
            if (channelCount == 1) AudioFormat.ENCODING_PCM_16BIT
            else AudioFormat.ENCODING_PCM_16BIT
        } else {
            AudioFormat.ENCODING_PCM_8BIT
        }
    }

    fun extractPcmData(wavData: ByteArray): ByteArray {
        if (wavData.size < 44) return wavData
        val header = String(wavData, 0, 4)
        if (header != "RIFF") return wavData

        var offset = 12
        while (offset + 8 <= wavData.size) {
            val chunkId = String(wavData, offset, 4)
            val chunkSize = wavData[offset + 4].toInt() and 0xFF or
                    (wavData[offset + 5].toInt() and 0xFF shl 8) or
                    (wavData[offset + 6].toInt() and 0xFF shl 16) or
                    (wavData[offset + 7].toInt() and 0xFF shl 24)
            if (chunkId == "data") {
                return wavData.copyOfRange(offset + 8, minOf(offset + 8 + chunkSize, wavData.size))
            }
            offset += 8 + chunkSize
            if (chunkSize % 2 != 0) offset++
        }
        return wavData.copyOfRange(44, wavData.size)
    }

    fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalSize = 36 + dataSize

        val wav = ByteArray(44 + dataSize)
        // RIFF header
        wav[0] = 'R'.code.toByte()
        wav[1] = 'I'.code.toByte()
        wav[2] = 'F'.code.toByte()
        wav[3] = 'F'.code.toByte()
        // File size
        wav[4] = (totalSize and 0xFF).toByte()
        wav[5] = ((totalSize shr 8) and 0xFF).toByte()
        wav[6] = ((totalSize shr 16) and 0xFF).toByte()
        wav[7] = ((totalSize shr 24) and 0xFF).toByte()
        // WAVE
        wav[8] = 'W'.code.toByte()
        wav[9] = 'A'.code.toByte()
        wav[10] = 'V'.code.toByte()
        wav[11] = 'E'.code.toByte()
        // fmt chunk
        wav[12] = 'f'.code.toByte()
        wav[13] = 'm'.code.toByte()
        wav[14] = 't'.code.toByte()
        wav[15] = ' '.code.toByte()
        // fmt size (16 for PCM)
        wav[16] = 16
        wav[17] = 0
        wav[18] = 0
        wav[19] = 0
        // Audio format (1 = PCM)
        wav[20] = 1
        wav[21] = 0
        // Channels
        wav[22] = (channels and 0xFF).toByte()
        wav[23] = ((channels shr 8) and 0xFF).toByte()
        // Sample rate
        wav[24] = (sampleRate and 0xFF).toByte()
        wav[25] = ((sampleRate shr 8) and 0xFF).toByte()
        wav[26] = ((sampleRate shr 16) and 0xFF).toByte()
        wav[27] = ((sampleRate shr 24) and 0xFF).toByte()
        // Byte rate
        wav[28] = (byteRate and 0xFF).toByte()
        wav[29] = ((byteRate shr 8) and 0xFF).toByte()
        wav[30] = ((byteRate shr 16) and 0xFF).toByte()
        wav[31] = ((byteRate shr 24) and 0xFF).toByte()
        // Block align
        wav[32] = (blockAlign and 0xFF).toByte()
        wav[33] = ((blockAlign shr 8) and 0xFF).toByte()
        // Bits per sample
        wav[34] = (bitsPerSample and 0xFF).toByte()
        wav[35] = ((bitsPerSample shr 8) and 0xFF).toByte()
        // data chunk
        wav[36] = 'd'.code.toByte()
        wav[37] = 'a'.code.toByte()
        wav[38] = 't'.code.toByte()
        wav[39] = 'a'.code.toByte()
        // Data size
        wav[40] = (dataSize and 0xFF).toByte()
        wav[41] = ((dataSize shr 8) and 0xFF).toByte()
        wav[42] = ((dataSize shr 16) and 0xFF).toByte()
        wav[43] = ((dataSize shr 24) and 0xFF).toByte()
        // PCM data
        System.arraycopy(pcmData, 0, wav, 44, dataSize)
        return wav
    }

    /** 生成一段静音 WAV（16-bit 单声道）。用于无可朗读内容或引擎不出声时占位，保留自然停顿、避免链路异常。 */
    fun silentWav(durationMs: Int = 400, sampleRate: Int = 24000): ByteArray {
        val byteCount = (sampleRate.toLong() * durationMs / 1000L * 2L).toInt().coerceAtLeast(0)
        return pcmToWav(ByteArray(byteCount), sampleRate, 1, 16)
    }
}
