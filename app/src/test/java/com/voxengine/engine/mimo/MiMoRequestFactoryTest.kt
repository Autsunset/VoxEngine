package com.voxengine.engine.mimo

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoRequestFactoryTest {
    private val context = "前一句：她哭喊着。后一句：他转身离开。"

    @Test
    fun `neighboring prose and context labels never enter requests for any model`() {
        for (model in listOf(MiMoTTSClient.MODEL_PRESET, MiMoTTSClient.MODEL_CLONE, MiMoTTSClient.MODEL_DESIGN)) {
            val request = MiMoRequestFactory.build("不要走。", "voice", model, "开心", context = context)
            val json = Gson().toJson(request)

            assertFalse(json.contains("上下文"))
            assertFalse(json.contains("她哭喊着"))
            assertFalse(json.contains("他转身离开"))
            assertEquals("不要走。", request.messages[1].content)
            assertEquals(if (model == MiMoTTSClient.MODEL_DESIGN) "voice" else "开心", request.messages[0].content)
        }
    }

    @Test
    fun `context becomes only a short emotion hint when no style is set`() {
        for (style in listOf(null, "", "无")) {
            val request = MiMoRequestFactory.build("不要走。", "茉莉", style = style, context = context)

            assertEquals("悲伤", request.messages[0].content)
            assertEquals("不要走。", request.messages[1].content)
        }
    }

    @Test
    fun `explicit style takes priority over context emotion`() {
        val request = MiMoRequestFactory.build("正文。", "茉莉", style = "  开心  ", context = context)

        assertEquals("开心", request.messages[0].content)
        assertEquals("正文。", request.messages[1].content)
    }

    @Test
    fun `disabled or neutral context does not add directions`() {
        for (context in listOf(null, "", "  ", "前文。当前段落。后文。")) {
            val request = MiMoRequestFactory.build("正文。", "茉莉", style = "无", context = context)

            assertEquals("", request.messages[0].content)
            assertEquals("正文。", request.messages[1].content)
        }
    }

    @Test
    fun `disabled context does not infer emotion from the spoken text alone`() {
        val request = MiMoRequestFactory.build("她哭喊着说不要走。", "茉莉", context = null)

        assertEquals("", request.messages[0].content)
        assertEquals("她哭喊着说不要走。", request.messages[1].content)
    }

    @Test
    fun `local context hints use supported styles only`() {
        val cues = mapOf(
            "他怒吼着。" to "生气",
            "她惊恐地发抖。" to "恐惧",
            "她哽咽着。" to "悲伤",
            "他笑道。" to "开心",
            "她惊呼一声。" to "惊讶",
            "他压低声音。" to "悄悄话",
            "她平静地回答。" to "平静"
        )
        for ((context, style) in cues) {
            val request = MiMoRequestFactory.build("正文。", "茉莉", context = context)
            assertEquals(style, request.messages[0].content)
            assertEquals("正文。", request.messages[1].content)
        }
    }

    @Test
    fun `context word in actual speech is retained`() {
        val text = "他解释了上下文的含义。"
        val request = MiMoRequestFactory.build(text, "茉莉", context = "前文。\n$text\n后文。")

        assertEquals("", request.messages[0].content)
        assertEquals(text, request.messages[1].content)
    }

    @Test
    fun `clone keeps reference audio separate from speech and context`() {
        val voice = "data:audio/wav;base64,reference"
        val request = MiMoRequestFactory.build("你好。", voice, MiMoTTSClient.MODEL_CLONE, context = context)

        assertEquals(voice, request.audio.voice)
        assertEquals("悲伤", request.messages[0].content)
        assertEquals("你好。", request.messages[1].content)
    }

    @Test
    fun `design keeps only the voice description in user directions`() {
        val voice = "温柔、清亮的成年女声"
        val request = MiMoRequestFactory.build("你好。", voice, MiMoTTSClient.MODEL_DESIGN, "开心", context = context)

        assertEquals(voice, request.messages[0].content)
        assertEquals("你好。", request.messages[1].content)
        assertNull(request.audio.voice)
    }

    @Test
    fun `design preview has no speech or context added to the voice description`() {
        val request = MiMoRequestFactory.build(
            "不使用的预览正文。", "温柔的成年女声", MiMoTTSClient.MODEL_DESIGN,
            optimizeTextPreview = true, context = context
        )

        assertEquals(1, request.messages.size)
        assertEquals("温柔的成年女声", request.messages[0].content)
        assertEquals(true, request.audio.optimizeTextPreview)
        assertNull(request.audio.voice)
        assertFalse(Gson().toJson(request).contains("不使用的预览正文"))
    }

    @Test
    fun `preset retains the existing chat completion contract`() {
        val request = MiMoRequestFactory.build("他说：你好。", "茉莉", style = "温柔", temperature = 0.2f)

        assertEquals(MiMoTTSClient.MODEL_PRESET, request.model)
        assertFalse(request.stream)
        assertEquals("wav", request.audio.format)
        assertEquals("茉莉", request.audio.voice)
        assertEquals(0.2f, request.temperature)
        assertEquals("user", request.messages[0].role)
        assertEquals("温柔", request.messages[0].content)
        assertEquals("assistant", request.messages[1].role)
        assertEquals("他说：你好。", request.messages[1].content)
        assertTrue(Gson().toJson(request).contains("\"temperature\":0.2"))
    }
}
