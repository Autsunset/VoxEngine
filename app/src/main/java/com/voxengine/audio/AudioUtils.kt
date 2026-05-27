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
}
