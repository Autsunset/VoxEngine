package com.voxengine.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HexEncodingTest {
    @Test
    fun encodesSignedBytesWithFixedWidthLowercase() {
        assertEquals("00010fff807f", HexEncoding.lower(byteArrayOf(0, 1, 15, -1, -128, 127)))
    }

    @Test
    fun encodesUppercaseAndEmptyInput() {
        assertEquals("00ABCDEF", HexEncoding.upper(byteArrayOf(0, -85, -51, -17)))
        assertEquals("", HexEncoding.lower(byteArrayOf()))
    }
}
