/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from rany2/edge-tts src/edge_tts/drm.py, revision
 * b2ffbdd9f43f28f03cc939b5de0f3964aa62d60e (2026-10-13).
 * Copyright (c) rany and edge-tts contributors.
 * Kotlin adaptation (c) 2026 VoxEngine contributors.
 * License texts and attribution are bundled in assets/licenses/.
 */
package com.voxengine.engine.edge

import com.voxengine.util.HexEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Edge's v2 token is CS64 + a modified SHA-256, not the v1 SHA-256 digest.
 * Int arithmetic intentionally wraps at 32 bits throughout both digest operations.
 */
internal object EdgeGecToken {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L

    fun generate(unixMs: Long): String {
        val seconds = Math.floorDiv(unixMs, 1000L) + WIN_EPOCH_SECONDS
        val ticks = (seconds - Math.floorMod(seconds, 300L)) * 10_000_000L
        val data = "$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        return "${HexEncoding.upper(cs64Digest(data, digest))}-${HexEncoding.upper(digest)}"
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        val words = IntArray(64)
        words[14] = readBigEndian(data, 0, 3)
        words[15] = readBigEndian(data, 3, 4)
        for (index in 16 until 30) {
            var value = INITIAL_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += sigma1(words[index - 2]) xor if (index == 16) 0x000B92C0 else 0
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += sigma0(words[index - 15]) xor if (index == 29) 0x059C0B80 else 0
            }
            words[index] = value
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            0xC9DC06A7.toInt() - ROUND_CONSTANTS[14]
        expandSchedule(words, 31)
        var state = rounds(INITIAL_WORKING_STATE, words, 14)
        for (index in state.indices) state[index] += INITIAL_STATE[index]

        // The protocol includes a precomputed 57-byte prefix in its SHA-256 state.
        val length = 57 + data.size
        val padding = Math.floorMod(55 - length, 64)
        val tail = ByteArray(data.size - 7 + 1 + padding + 8)
        data.copyInto(tail, 0, 7)
        tail[data.size - 7] = 0x80.toByte()
        ByteBuffer.wrap(tail).putLong(tail.size - 8, length.toLong() * 8)
        for (offset in tail.indices step 64) {
            for (index in 0 until 16) words[index] = readBigEndian(tail, offset + index * 4, 4)
            expandSchedule(words, 16)
            val working = rounds(state, words, 0)
            for (index in state.indices) state[index] += working[index]
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }

    private fun expandSchedule(words: IntArray, firstWord: Int) {
        for (index in firstWord until 64) {
            words[index] = sigma1(words[index - 2]) + words[index - 7] +
                sigma0(words[index - 15]) + words[index - 16]
        }
    }

    private fun rounds(state: IntArray, words: IntArray, firstRound: Int): IntArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (index in firstRound until 64) {
            val constant = if (firstRound == 14 && index == 14) {
                0xC9DC06A7.toInt()
            } else {
                ROUND_CONSTANTS[index]
            }
            val sum1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor
                Integer.rotateRight(e, 25)
            val choice = g xor (e and (f xor g))
            val t1 = h + sum1 + choice + constant + words[index]
            val sum0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor
                Integer.rotateRight(a, 22)
            val majority = (a and b) or (c and (a or b))
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + sum0 + majority
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): ByteArray {
        val digestBuffer = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = digestBuffer.int or 1
        val keyB = digestBuffer.int or 1
        val factors = intArrayOf(keyA * 0x82893933.toInt(), keyB * 0x82893933.toInt())
        val terms = ArrayList<Int>()
        val offsets = ArrayList<Int>()
        for (index in CS64_COEFFICIENTS.indices step 2) {
            terms += CS64_COEFFICIENTS[index] * factors[(index / 2) % 2]
            offsets += CS64_COEFFICIENTS[index + 1]
        }
        val padded = ByteArray(((data.size + 1 + 7) / 8) * 8)
        data.copyInto(padded, 1)
        val buffer = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until padded.size / 4) {
            val word = buffer.int
            var term = word * if (index % 2 == 0) keyA else keyB
            var offset = Integer.rotateLeft(word, 16) *
                if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt()
            if (index == 0) {
                term += 0xF2F90B1A.toInt() * factors[0]
                offset += 0x0B1A0000
            }
            terms += term
            offsets += offset
        }
        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        for (index in terms.indices step 2) {
            val x = terms[index]
            val y = terms[index + 1]
            var q = Integer.rotateLeft(offsets[index] + x, 16) * 0x76F50F34
            swapAcc += Integer.rotateLeft(q, 16) * 0xC3EFEA97.toInt() + q
            q = Integer.rotateLeft(offsets[index + 1] + y, 16) * 0x1881CE3C
            swapLast = Integer.rotateLeft(q, 16) * 0x35BD1EC9 + q
            swapAcc += swapLast

            state = state * keyA + x
            state = Integer.rotateLeft(state, 16) * 0xCF98B111.toInt()
            state = Integer.rotateLeft(state, 16) * 0x87085B9F.toInt()
            state = Integer.rotateLeft(state, 16) * 0x12CEB96D
            state = Integer.rotateLeft(state, 16) * 0x257E1D83
            acc += state
            var t = state * keyB + y
            t = Integer.rotateLeft(t, 16) * 0xA27416F5.toInt()
            t = Integer.rotateLeft(t, 16) * 0xD38396FF.toInt()
            state = Integer.rotateLeft(
                Integer.rotateLeft(t, 16) * 0x7C932B89, 16
            ) * 0xBFA49F69.toInt()
            acc += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(swapLast xor state).putInt(swapAcc xor acc).array()
    }

    private fun readBigEndian(data: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (index in offset until offset + length) {
            value = (value shl 8) or (data[index].toInt() and 0xFF)
        }
        return value
    }

    private fun sigma0(value: Int): Int =
        Integer.rotateRight(value, 7) xor Integer.rotateRight(value, 18) xor (value ushr 3)

    private fun sigma1(value: Int): Int =
        Integer.rotateRight(value, 17) xor Integer.rotateRight(value, 19) xor (value ushr 10)

    private val INITIAL_STATE = longArrayOf(
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    ).map { it.toInt() }.toIntArray()
    private val INITIAL_WORKING_STATE = longArrayOf(
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2
    ).map { it.toInt() }.toIntArray()
    private val INITIAL_SCHEDULE = longArrayOf(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491,
        0xC0DD25A0, 0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060, 0x72766564
    ).map { it.toInt() }.toIntArray()
    private val ROUND_CONSTANTS = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1,
        0x923F82A4, 0xAB1C5ED5, 0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174, 0xE49B69C1, 0xEFBE4786,
        0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147,
        0x06CA6351, 0x14292967, 0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85, 0xA2BFE8A1, 0xA81A664B,
        0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A,
        0x5B9CCA4F, 0x682E6FF3, 0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208,
        0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2
    ).map { it.toInt() }.toIntArray()
    private val CS64_COEFFICIENTS = longArrayOf(
        0xE82E91B1, 0xB32700D0, 0x44DE24C6, 0x88DFF51D, 0x3BEB65BC, 0x29514038,
        0xC89C5933, 0xB328CBDF, 0x8D1EC211, 0xD405B4D5, 0x314EF5D5, 0x26E55F3C,
        0xE92491B1, 0x607801C6, 0xF0A5B9D5, 0x82B2C1FB, 0x06064CF3, 0xFA51A3DA,
        0xDA79FA60, 0x207CC464, 0xBED20560, 0x3C3C74F3, 0x89F9C9F8, 0xD2D14B61,
        0x35FA38C6, 0xBB04A2C1, 0xCB49E3C6, 0xC5626167
    ).map { it.toInt() }.toIntArray()
}
