package com.voxengine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioUtilsTest {
    @Test
    fun pcmWavRoundTripPreservesFormatAndSamples() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        val parsed = AudioUtils.parseWav(AudioUtils.pcmToWav(pcm, 22_050, 2, 16))

        assertEquals(22_050, parsed.sampleRate)
        assertEquals(2, parsed.channelCount)
        assertEquals(16, parsed.bitsPerSample)
        assertArrayEquals(pcm, parsed.pcmData)
    }

    @Test
    fun shortRiffHeaderUsesDefaultsWithoutReadingPastEnd() {
        val truncated = ByteArray(24).apply {
            writeAscii(0, "RIFF")
            writeAscii(8, "WAVE")
        }

        val parsed = AudioUtils.parseWav(truncated)

        assertEquals(24_000, parsed.sampleRate)
        assertEquals(1, parsed.channelCount)
        assertEquals(16, parsed.bitsPerSample)
        assertTrue(parsed.pcmData.isEmpty())
    }

    @Test
    fun parserFindsFmtAndDataAfterExtensionChunk() {
        val pcm = byteArrayOf(9, 8, 7, 6)
        val standard = AudioUtils.pcmToWav(pcm, 16_000, 1, 16)
        val junk = byteArrayOf('J'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(), 3, 0, 0, 0, 1, 2, 3, 0)
        val extended = standard.copyOfRange(0, 12) + junk + standard.copyOfRange(12, standard.size)

        val parsed = AudioUtils.parseWav(extended)

        assertEquals(16_000, parsed.sampleRate)
        assertArrayEquals(pcm, parsed.pcmData)
    }

    @Test
    fun truncatedDataChunkOnlyReturnsAvailableBytes() {
        val wav = AudioUtils.pcmToWav(byteArrayOf(1, 2, 3), 24_000, 1, 16).apply {
            this[40] = 0xFF.toByte()
            this[41] = 0xFF.toByte()
            this[42] = 0xFF.toByte()
            this[43] = 0x7F
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), AudioUtils.extractPcmData(wav))
    }

    @Test
    fun rawPcmInputIsReturnedWithoutModification() {
        val raw = byteArrayOf(4, 3, 2, 1)

        val parsed = AudioUtils.parseWav(raw)

        assertArrayEquals(raw, parsed.pcmData)
        assertEquals(24_000, parsed.sampleRate)
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
    }
}
