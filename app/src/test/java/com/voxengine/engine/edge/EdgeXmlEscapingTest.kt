package com.voxengine.engine.edge

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeXmlEscapingTest {
    @Test
    fun escapesAllXmlSpecialCharactersInOnePass() {
        assertEquals(
            "A&amp;B&lt;C&gt;D&quot;E&apos;F",
            escapeEdgeXml("A&B<C>D\"E'F")
        )
    }

    @Test
    fun keepsOrdinaryMultilingualTextUnchanged() {
        assertEquals("你好，Edge TTS。", escapeEdgeXml("你好，Edge TTS。"))
    }

    @Test
    fun extractsLocaleFromTwoOrThreePartVoiceIds() {
        assertEquals("en-US", localeFromEdgeVoice("en-US"))
        assertEquals("ja-JP", localeFromEdgeVoice("ja-JP-NanamiNeural"))
        assertEquals("zh-CN", localeFromEdgeVoice("invalid"))
    }
}
