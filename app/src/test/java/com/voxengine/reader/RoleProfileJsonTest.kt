package com.voxengine.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 RoleProfile 的 Gson 往返——Map<String,RoleVoiceStyle> 的值类型必须还原为 RoleVoiceStyle，否则 UI 取 vs.voice 会崩。 */
class RoleProfileJsonTest {

    @Test
    fun roundTripPreservesNarrationAndDialogue() {
        val original = RoleProfile(
            narration = RoleVoiceStyle("白桦", "深沉"),
            dialogue = RoleVoiceStyle("冰糖", null)
        )
        val parsed = RoleProfileJson.parse(RoleProfileJson.serialize(original))

        assertEquals("白桦", parsed.narration.voice)
        assertEquals("深沉", parsed.narration.style)
        assertEquals("冰糖", parsed.dialogue.voice)
        assertTrue(parsed.characters.isEmpty())
    }

    @Test
    fun roundTripPreservesCharacterMapWithValueType() {
        val original = RoleProfile(
            characters = mapOf(
                "张三" to RoleVoiceStyle("苏打", "严肃"),
                "李四" to RoleVoiceStyle("茉莉", null)
            )
        )
        val parsed = RoleProfileJson.parse(RoleProfileJson.serialize(original))

        assertEquals(2, parsed.characters.size)
        // 关键：取出来必须是 RoleVoiceStyle，能访问 .voice，否则运行期 ClassCastException/NoSuchMethodError
        val zhang = parsed.characters["张三"]
        assertNotNull(zhang)
        assertTrue("Map 值类型应为 RoleVoiceStyle，实为 ${zhang!!::class.java.name}", zhang is RoleVoiceStyle)
        assertEquals("苏打", zhang.voice)
        assertEquals("严肃", zhang.style)
        assertEquals("茉莉", parsed.characters["李四"]?.voice)
    }

    @Test
    fun parseEmptyOrDefaultJsonDoesNotCrash() {
        assertEquals(RoleProfile(), RoleProfileJson.parse(""))
        assertEquals(RoleProfile(), RoleProfileJson.parse(null))
        // 损坏 JSON 应回落到空档而非崩溃
        assertEquals(RoleProfile(), RoleProfileJson.parse("{not valid"))
    }
}
