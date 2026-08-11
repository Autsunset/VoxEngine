package com.voxengine.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.voxengine.VoxEngineApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 用 Android 系统 MediaCodec 把 MP3 解码为 16-bit PCM。
 * Edge 免费 readaloud 端点只返回 MP3，而本应用播放链路统一消费 PCM/WAV，故需解码。
 */
object Mp3Decoder {

    data class DecodedPcm(val pcm: ByteArray, val sampleRate: Int, val channelCount: Int)

    fun decodeToPcm(mp3: ByteArray): DecodedPcm {
        val tempFile = File.createTempFile("edge_tts_", ".mp3", VoxEngineApplication.instance.cacheDir)
        try {
            tempFile.writeBytes(mp3)
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(tempFile.absolutePath)

                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        trackIndex = i
                        format = f
                        break
                    }
                }
                if (trackIndex < 0 || format == null) throw IOException("MP3 中未找到音频轨道")
                extractor.selectTrack(trackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME)!!
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()

                val output = ByteArrayOutputStream()
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                while (!sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val outBuffer = decoder.getOutputBuffer(outIndex)!!
                            if (bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outBuffer.position(bufferInfo.offset)
                                outBuffer.get(chunk)
                                output.write(chunk)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = decoder.outputFormat
                            sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
                return DecodedPcm(output.toByteArray(), sampleRate, channelCount)
            } finally {
                codec?.let { decoder ->
                    runCatching { decoder.stop() }
                    runCatching { decoder.release() }
                }
                runCatching { extractor.release() }
            }
        } finally {
            tempFile.delete()
        }
    }
}
