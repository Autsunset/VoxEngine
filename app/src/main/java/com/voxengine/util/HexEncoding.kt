package com.voxengine.util

/** Fixed-width hexadecimal encoding without per-byte formatter allocations. */
object HexEncoding {
    private const val LOWER_DIGITS = "0123456789abcdef"
    private const val UPPER_DIGITS = "0123456789ABCDEF"

    fun lower(bytes: ByteArray): String = encode(bytes, LOWER_DIGITS)

    fun upper(bytes: ByteArray): String = encode(bytes, UPPER_DIGITS)

    private fun encode(bytes: ByteArray, digits: String): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            chars[index * 2] = digits[value ushr 4]
            chars[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(chars)
    }
}
