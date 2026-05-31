package com.voxengine.audio

import kotlin.math.abs

/**
 * 变速不变调处理器：基于公有领域 Sonic 算法（Bill Cox, github.com/waywardgeek/sonic）的精简 Kotlin 移植。
 * 仅实现 speed（语速）变换，pitch/rate/volume 固定为 1，始终全分辨率（高质量）匹配，不做降采样。
 * 输入/输出均为 16-bit PCM（小端、交错多声道）。
 *
 * 用于系统 TTS 路径：该路径把 PCM 交给第三方 App 播放，无法用 AudioTrack.playbackParams 变速，
 * 因此需要在 PCM 域自行变速。应用内播放（Test/Reader）直接用 AudioTrack.playbackParams 即可。
 */
class SpeedAdjuster(
    private val sampleRate: Int,
    private val numChannels: Int
) {
    private val minPeriod = sampleRate / SONIC_MAX_PITCH
    private val maxPeriod = sampleRate / SONIC_MIN_PITCH
    private val maxRequired = 2 * maxPeriod

    private var inputBuffer = ShortArray(maxRequired * numChannels)
    private var outputBuffer = ShortArray(maxRequired * numChannels)

    private var numInputSamples = 0   // 每声道样本数
    private var numOutputSamples = 0  // 每声道样本数
    private var remainingInputToCopy = 0
    private var speed = 1.0f

    private var minDiff = 0
    private var maxDiff = 0
    private var prevMinDiff = 0
    private var prevPeriod = 0

    private fun setSpeed(s: Float) { speed = s }

    private fun enlargeInputBufferIfNeeded(numSamples: Int) {
        val cap = inputBuffer.size / numChannels
        if (numInputSamples + numSamples > cap) {
            val newCap = cap + cap / 2 + numSamples
            inputBuffer = inputBuffer.copyOf(newCap * numChannels)
        }
    }

    private fun enlargeOutputBufferIfNeeded(numSamples: Int) {
        val cap = outputBuffer.size / numChannels
        if (numOutputSamples + numSamples > cap) {
            val newCap = cap + cap / 2 + numSamples
            outputBuffer = outputBuffer.copyOf(newCap * numChannels)
        }
    }

    private fun writeShortToStream(samples: ShortArray, perChannelCount: Int) {
        enlargeInputBufferIfNeeded(perChannelCount)
        System.arraycopy(samples, 0, inputBuffer, numInputSamples * numChannels, perChannelCount * numChannels)
        numInputSamples += perChannelCount
        processStreamInput()
    }

    private fun removeInputSamples(position: Int) {
        val remaining = numInputSamples - position
        if (remaining > 0) {
            System.arraycopy(inputBuffer, position * numChannels, inputBuffer, 0, remaining * numChannels)
        }
        numInputSamples = remaining
    }

    /** 把 [srcOff]（每声道偏移）起的 [numSamples] 个样本拷到输出。 */
    private fun copyToOutput(src: ShortArray, srcOff: Int, numSamples: Int) {
        enlargeOutputBufferIfNeeded(numSamples)
        System.arraycopy(src, srcOff * numChannels, outputBuffer, numOutputSamples * numChannels, numSamples * numChannels)
        numOutputSamples += numSamples
    }

    private fun copyInputToOutput(position: Int): Int {
        var numSamples = remainingInputToCopy
        if (numSamples > maxRequired) numSamples = maxRequired
        copyToOutput(inputBuffer, position, numSamples)
        remainingInputToCopy -= numSamples
        return numSamples
    }

    private fun overlapAdd(
        numSamples: Int,
        out: ShortArray, outOff: Int,
        rampDown: ShortArray, rampDownOff: Int,
        rampUp: ShortArray, rampUpOff: Int
    ) {
        for (ch in 0 until numChannels) {
            var o = outOff * numChannels + ch
            var d = rampDownOff * numChannels + ch
            var u = rampUpOff * numChannels + ch
            for (t in 0 until numSamples) {
                out[o] = ((rampDown[d].toInt() * (numSamples - t) + rampUp[u].toInt() * t) / numSamples).toShort()
                o += numChannels; d += numChannels; u += numChannels
            }
        }
    }

    /** 在 [minP, maxP] 范围内用 AMDF 寻找音高周期；副作用：设置 minDiff/maxDiff。 */
    private fun findPitchPeriodInRange(samples: ShortArray, off: Int, minP: Int, maxP: Int): Int {
        var bestPeriod = 0
        var worstPeriod = 255
        var localMinDiff = 1
        var localMaxDiff = 0
        var period = minP
        while (period <= maxP) {
            var diff = 0
            var s = off * numChannels
            var p = (off + period) * numChannels
            var i = 0
            while (i < period) {
                val sVal = samples[s].toInt()
                val pVal = samples[p].toInt()
                diff += if (sVal >= pVal) sVal - pVal else pVal - sVal
                s += numChannels; p += numChannels; i++
            }
            if (bestPeriod == 0 || diff.toLong() * bestPeriod < localMinDiff.toLong() * period) {
                localMinDiff = diff
                bestPeriod = period
            }
            if (diff.toLong() * worstPeriod > localMaxDiff.toLong() * period) {
                localMaxDiff = diff
                worstPeriod = period
            }
            period++
        }
        minDiff = if (bestPeriod > 0) localMinDiff / bestPeriod else localMinDiff
        maxDiff = if (worstPeriod > 0) localMaxDiff / worstPeriod else localMaxDiff
        return bestPeriod
    }

    private fun prevPeriodBetter(preferNewPeriod: Boolean): Boolean {
        if (minDiff == 0 || prevPeriod == 0) return false
        if (preferNewPeriod) {
            if (maxDiff > minDiff * 3) return false
            if (minDiff * 2 <= prevMinDiff * 3) return false
        } else {
            if (minDiff <= prevMinDiff) return false
        }
        return true
    }

    private fun findPitchPeriod(samples: ShortArray, off: Int, preferNewPeriod: Boolean): Int {
        val period = findPitchPeriodInRange(samples, off, minPeriod, maxPeriod)
        val retPeriod = if (prevPeriodBetter(preferNewPeriod)) prevPeriod else period
        prevMinDiff = minDiff
        prevPeriod = period
        return retPeriod
    }

    private fun skipPitchPeriod(samples: ShortArray, off: Int, speed: Float, period: Int): Int {
        val newSamples: Int
        if (speed >= 2.0f) {
            newSamples = (period / (speed - 1.0f)).toInt()
        } else {
            newSamples = period
            remainingInputToCopy = (period * (2.0f - speed) / (speed - 1.0f)).toInt()
        }
        enlargeOutputBufferIfNeeded(newSamples)
        overlapAdd(newSamples, outputBuffer, numOutputSamples, samples, off, samples, off + period)
        numOutputSamples += newSamples
        return newSamples
    }

    private fun insertPitchPeriod(samples: ShortArray, off: Int, speed: Float, period: Int): Int {
        val newSamples: Int
        if (speed < 0.5f) {
            newSamples = (period * speed / (1.0f - speed)).toInt()
        } else {
            newSamples = period
            remainingInputToCopy = (period * (2.0f * speed - 1.0f) / (1.0f - speed)).toInt()
        }
        enlargeOutputBufferIfNeeded(period + newSamples)
        System.arraycopy(samples, off * numChannels, outputBuffer, numOutputSamples * numChannels, period * numChannels)
        overlapAdd(newSamples, outputBuffer, numOutputSamples + period, samples, off + period, samples, off)
        numOutputSamples += period + newSamples
        return newSamples
    }

    private fun changeSpeed(speed: Float) {
        if (numInputSamples < maxRequired) return
        val numSamples = numInputSamples
        var position = 0
        do {
            if (remainingInputToCopy > 0) {
                position += copyInputToOutput(position)
            } else {
                val period = findPitchPeriod(inputBuffer, position, true)
                position += if (speed > 1.0f) {
                    period + skipPitchPeriod(inputBuffer, position, speed, period)
                } else {
                    insertPitchPeriod(inputBuffer, position, speed, period)
                }
            }
        } while (position + maxRequired <= numSamples)
        removeInputSamples(position)
    }

    private fun processStreamInput() {
        if (speed > 1.00001f || speed < 0.99999f) {
            changeSpeed(speed)
        } else {
            copyToOutput(inputBuffer, 0, numInputSamples)
            numInputSamples = 0
        }
    }

    private fun flushStream() {
        val remainingSamples = numInputSamples
        val expectedOutputSamples = numOutputSamples + (remainingSamples / speed + 0.5f).toInt()
        // 补足静音以冲刷输入缓冲
        enlargeInputBufferIfNeeded(2 * maxRequired)
        for (i in 0 until 2 * maxRequired * numChannels) {
            inputBuffer[numInputSamples * numChannels + i] = 0
        }
        numInputSamples += 2 * maxRequired
        processStreamInput()
        if (numOutputSamples > expectedOutputSamples) {
            numOutputSamples = expectedOutputSamples
        }
        numInputSamples = 0
        remainingInputToCopy = 0
    }

    private fun readOutput(): ShortArray {
        val out = ShortArray(numOutputSamples * numChannels)
        System.arraycopy(outputBuffer, 0, out, 0, out.size)
        return out
    }

    companion object {
        private const val SONIC_MIN_PITCH = 65
        private const val SONIC_MAX_PITCH = 400

        /**
         * 对一段 16-bit 小端 PCM 做变速不变调。speed≈1 或数据过短时原样返回。
         */
        fun process(pcm16le: ByteArray, sampleRate: Int, numChannels: Int, speed: Float): ByteArray {
            if (speed <= 0f || abs(speed - 1.0f) < 0.01f) return pcm16le
            val frameBytes = 2 * numChannels
            if (pcm16le.size < frameBytes * 4) return pcm16le

            val adjuster = SpeedAdjuster(sampleRate, numChannels)
            adjuster.setSpeed(speed)
            val input = bytesToShorts(pcm16le)
            adjuster.writeShortToStream(input, input.size / numChannels)
            adjuster.flushStream()
            return shortsToBytes(adjuster.readOutput())
        }

        private fun bytesToShorts(bytes: ByteArray): ShortArray {
            val n = bytes.size / 2
            val shorts = ShortArray(n)
            for (i in 0 until n) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                shorts[i] = ((hi shl 8) or lo).toShort()
            }
            return shorts
        }

        private fun shortsToBytes(shorts: ShortArray): ByteArray {
            val bytes = ByteArray(shorts.size * 2)
            for (i in shorts.indices) {
                val v = shorts[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return bytes
        }
    }
}
