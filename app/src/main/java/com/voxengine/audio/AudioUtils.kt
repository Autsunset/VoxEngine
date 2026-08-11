package com.voxengine.audio

object AudioUtils {
    private const val DEFAULT_SAMPLE_RATE = 24_000
    private const val DEFAULT_CHANNEL_COUNT = 1
    private const val DEFAULT_BITS_PER_SAMPLE = 16

    /**
     * 一次解析出播放所需的全部 WAV 信息，供热路径避免对同一文件重复扫描。
     * 非 WAV 输入按 24 kHz/单声道/PCM16 原始数据处理；损坏的 WAV 安全返回空 PCM。
     */
    data class ParsedWav(
        val sampleRate: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val pcmData: ByteArray
    )

    fun parseWav(wavData: ByteArray): ParsedWav {
        val layout = parseLayout(wavData)
        val pcm = if (layout.dataLength == wavData.size && layout.dataOffset == 0) {
            wavData
        } else if (layout.dataLength > 0) {
            wavData.copyOfRange(layout.dataOffset, layout.dataOffset + layout.dataLength)
        } else {
            ByteArray(0)
        }
        return ParsedWav(layout.sampleRate, layout.channelCount, layout.bitsPerSample, pcm)
    }

    fun getWavSampleRate(wavData: ByteArray): Int = parseLayout(wavData).sampleRate

    fun getWavChannelCount(wavData: ByteArray): Int = parseLayout(wavData).channelCount

    fun getWavBitsPerSample(wavData: ByteArray): Int = parseLayout(wavData).bitsPerSample

    fun extractPcmData(wavData: ByteArray): ByteArray = parseWav(wavData).pcmData

    /**
     * RIFF 的 fmt/data 块不保证固定在 12/36 字节处。按块扫描既兼容 JUNK/LIST 等扩展块，
     * 又用 Long 做边界计算，避免恶意或截断 chunk size 导致整数溢出、越界或死循环。
     */
    private fun parseLayout(data: ByteArray): WavLayout {
        if (data.size < 12 || !data.matchesAscii(0, "RIFF") || !data.matchesAscii(8, "WAVE")) {
            return WavLayout(
                sampleRate = DEFAULT_SAMPLE_RATE,
                channelCount = DEFAULT_CHANNEL_COUNT,
                bitsPerSample = DEFAULT_BITS_PER_SAMPLE,
                dataOffset = 0,
                dataLength = data.size
            )
        }

        var sampleRate = DEFAULT_SAMPLE_RATE
        var channelCount = DEFAULT_CHANNEL_COUNT
        var bitsPerSample = DEFAULT_BITS_PER_SAMPLE
        var dataOffset = -1
        var dataLength = 0
        var offset = 12

        while (offset <= data.size - 8) {
            val declaredSize = data.readUInt32Le(offset + 4)
            val payloadOffset = offset + 8
            val available = data.size - payloadOffset
            val readableSize = minOf(declaredSize, available.toLong()).toInt()

            when {
                data.matchesAscii(offset, "fmt ") && readableSize >= 16 -> {
                    val parsedChannels = data.readUInt16Le(payloadOffset + 2)
                    val parsedSampleRate = data.readUInt32Le(payloadOffset + 4)
                    val parsedBits = data.readUInt16Le(payloadOffset + 14)
                    if (parsedChannels in 1..8) channelCount = parsedChannels
                    if (parsedSampleRate in 4_000L..384_000L) sampleRate = parsedSampleRate.toInt()
                    if (parsedBits in setOf(8, 16, 24, 32)) bitsPerSample = parsedBits
                }
                dataOffset < 0 && data.matchesAscii(offset, "data") -> {
                    dataOffset = payloadOffset
                    dataLength = readableSize
                }
            }

            // 截断块已消费全部剩余数据，不能再信任其声明长度继续跳转。
            if (declaredSize > available.toLong()) break
            val nextOffset = payloadOffset.toLong() + declaredSize + (declaredSize and 1L)
            if (nextOffset <= offset.toLong() || nextOffset > data.size.toLong()) break
            offset = nextOffset.toInt()
        }

        return WavLayout(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset.coerceAtLeast(0),
            dataLength = if (dataOffset >= 0) dataLength else 0
        )
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean {
        if (offset < 0 || offset + value.length > size) return false
        for (index in value.indices) {
            if (this[offset + index].toInt() != value[index].code) return false
        }
        return true
    }

    private fun ByteArray.readUInt16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.readUInt32Le(offset: Int): Long =
        (this[offset].toLong() and 0xFFL) or
            ((this[offset + 1].toLong() and 0xFFL) shl 8) or
            ((this[offset + 2].toLong() and 0xFFL) shl 16) or
            ((this[offset + 3].toLong() and 0xFFL) shl 24)

    private data class WavLayout(
        val sampleRate: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataLength: Int
    )

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
