package com.voxengine.reader

import com.voxengine.util.SpeechTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleSegmenterTest {

    @Test
    fun pureNarrationProducesSingleNarrationSegment() {
        val segments = RoleSegmenter.segment("天空湛蓝，阳光洒在街道上。")

        assertEquals(1, segments.size)
        assertEquals(SpeechRole.NARRATION, segments[0].role)
        assertNull(segments[0].character)
    }

    @Test
    fun dialogueIsSplitFromNarrationAndKeepsQuotes() {
        val segments = RoleSegmenter.segment("张三走了过来，说道：“你好啊。”")

        assertEquals(2, segments.size)
        assertEquals(SpeechRole.NARRATION, segments[0].role)
        assertEquals(SpeechRole.DIALOGUE, segments[1].role)
        // 对话片段保留引号，朗读时保留对话语气（与原单音色路径一致）
        assertTrue(segments[1].text.startsWith("“"))
        assertTrue(segments[1].text.endsWith("”"))
    }

    @Test
    fun noActualCharactersDroppedOnlyBoundaryWhitespace() {
        val text = "他笑了笑，转身说道：“今天天气不错。”然后继续往前走。"
        val segments = RoleSegmenter.segment(text)

        // 各片段 trim 了边界空白（对合成无害），但不应丢失任何实际字符
        val expected = SpeechTextNormalizer.normalize(text).filter { !it.isWhitespace() }
        val actual = segments.joinToString("") { it.text }.filter { !it.isWhitespace() }
        assertEquals(expected, actual)
    }

    @Test
    fun speakerExtractedFromNarrationPrefix() {
        val segments = RoleSegmenter.segment("张三笑道：“你好。”", setOf("张三"))
        val dialogue = segments.first { it.role == SpeechRole.DIALOGUE }

        assertEquals("张三", dialogue.character)
    }

    @Test
    fun speakerIsNullWhenNarrationHasNoSpeakingVerb() {
        // 旁白以句号结尾、不含"XX说"类动词，不应误抓说话人
        val segments = RoleSegmenter.segment("夜深了。“是鬼吗？”")
        val dialogue = segments.first { it.role == SpeechRole.DIALOGUE }

        assertEquals(SpeechRole.DIALOGUE, dialogue.role)
        assertNull(dialogue.character)
    }

    @Test
    fun bracketQuotesAreNormalizedToCurlyAndSegmented() {
        // 【】经 normalize 后变为 “”，应被识别为对话
        val segments = RoleSegmenter.segment("旁白部分。【这是内心独白。】继续旁白。")

        assertEquals(3, segments.size)
        assertEquals(SpeechRole.NARRATION, segments[0].role)
        assertEquals(SpeechRole.DIALOGUE, segments[1].role)
        assertEquals(SpeechRole.NARRATION, segments[2].role)
    }

    @Test
    fun unclosedQuoteAtEndTreatedAsDialogue() {
        val segments = RoleSegmenter.segment("他说：“还没说完呢")

        assertEquals(2, segments.size)
        assertEquals(SpeechRole.NARRATION, segments[0].role)
        assertEquals(SpeechRole.DIALOGUE, segments[1].role)
    }

    @Test
    fun blankInputProducesNoSegments() {
        assertTrue(RoleSegmenter.segment("").isEmpty())
        // 纯空白经 normalize/trim 后为空，不应产出片段
        assertTrue(RoleSegmenter.segment("    ").isEmpty())
    }

    @Test
    fun symbolOnlySegmentIsFilteredOut() {
        // "..." 纯符号段没有可朗读内容，应跳过
        assertTrue(RoleSegmenter.segment("...").isEmpty())
        // "——" 破折号也没有可朗读内容
        assertTrue(RoleSegmenter.segment("——").isEmpty())
        // 混合符号
        assertTrue(RoleSegmenter.segment("... . ~").isEmpty())
    }

    @Test
    fun symbolWithLetterIsKept() {
        // 包含至少一个字母/数字的段落不应被过滤
        val segments = RoleSegmenter.segment("嗯.")
        assertEquals(1, segments.size)
        assertEquals("嗯.", segments[0].text)
    }

    @Test
    fun voiceForResolvesByRoleAndCharacterMap() {
        val narration = RoleSegment(SpeechRole.NARRATION, null, "n")
        val dialogue = RoleSegment(SpeechRole.DIALOGUE, null, "d")
        val named = RoleSegment(SpeechRole.DIALOGUE, "张三", "dz")

        assertEquals("旁白音", RoleSegmenter.voiceFor(narration, "旁白音", "对话音", mapOf("张三" to "张三音"), "默认"))
        assertEquals("对话音", RoleSegmenter.voiceFor(dialogue, "旁白音", "对话音", mapOf("张三" to "张三音"), "默认"))
        assertEquals("张三音", RoleSegmenter.voiceFor(named, "旁白音", "对话音", mapOf("张三" to "张三音"), "默认"))
        // 未配置对话音时回落到 fallback
        assertEquals("默认", RoleSegmenter.voiceFor(dialogue, "旁白音", null, emptyMap(), "默认"))
    }



    @Test
    fun speakerReverseLookupFindsNameNotAdjacentToVerb() {
        // “陈拾安听着也来了兴趣，扭头笑问道：” —— 正则可能误抓“扭头”，反查应命中“陈拾安”
        val segments = RoleSegmenter.segment(
            "陈拾安听着也来了兴趣，扭头笑问道：“婉音姐还有校服？”",
            setOf("陈拾安", "婉音姐")
        )
        val dialogue = segments.first { it.role == SpeechRole.DIALOGUE }
        assertEquals("陈拾安", dialogue.character)
    }

    @Test
    fun speakerRegexMatchInConfiguredNamesIsPreferred() {
        // 正则在旁白尾部精确匹配到“李四问道：” → 命中
        val segments = RoleSegmenter.segment(
            "李四问道：“来了啊。”",
            setOf("李四", "王五")
        )
        val dialogue = segments.first { it.role == SpeechRole.DIALOGUE }
        assertEquals("李四", dialogue.character)
    }

    @Test
    fun speakerNotInConfiguredNamesReturnsNull() {
        // 正则匹配到“张三”，但不在配置名集合中 → 返回 null
        val segments = RoleSegmenter.segment(
            "张三笑道：“你好。”",
            setOf("李四")
        )
        val dialogue = segments.first { it.role == SpeechRole.DIALOGUE }
        assertEquals(SpeechRole.DIALOGUE, dialogue.role)
        org.junit.Assert.assertNull(dialogue.character)
    }

    @Test
    fun speakerClosestConfiguredNameWinsWhenMultiplePresent() {
        // 两个名字都出现，“王五”更靠后 → 应命中“王五”
        val segments = RoleSegmenter.segment(
            "张三在旁边听着，王五笑道：“说得好。”",
            setOf("张三", "王五")
        )
        val dialogue = segments.first { it.role == SpeechRole.DIALOGUE }
        assertEquals("王五", dialogue.character)
    }
}