package com.voxengine.reader

import org.junit.Assert.*
import org.junit.Test

class RoleMatchRuleTest {
    @Test fun aliasRuleOverridesDefaultAndPreservesText() {
        val text = "小张握紧拳头：\u201c我不答应！\u201d"
        val segments = RoleSegmenter.segment(text, setOf("张三"), listOf(RoleMatchRule("张三", "小张.*")))
        assertEquals("张三", segments.last().character)
        assertEquals(text, segments.joinToString("") { it.text })
    }

    @Test fun afterDialogueRulesAndDisabledRules() {
        val text = "\u201c可以。\u201d张公子点头。"
        val rules = listOf(RoleMatchRule("张三", "张公子.*", afterDialogue = true))
        assertEquals("张三", RoleSegmenter.segment(text, setOf("张三"), rules).first().character)
        assertNull(RoleSegmenter.segment(text, setOf("张三"), rules.map { it.copy(enabled = false) }).first().character)
    }

    @Test fun oldProfilesAndRulesRoundTrip() {
        assertTrue(RoleProfileJson.parse("{\"characters\":{\"张三\":{\"voice\":\"苏打\"}}}").matchRules.isEmpty())
        val profile = RoleProfile(characters = mapOf("张三" to RoleVoiceStyle("苏打")), matchRules = listOf(RoleMatchRule("张三", "小张.*")))
        assertEquals(profile, RoleProfileJson.import(RoleProfileJson.serialize(profile)))
    }

    @Test fun invalidImportCannotEraseConfiguration() {
        for (json in listOf("{}", "{\"characters\":null}", "{\"matchRules\":[{\"character\":\"张三\",\"pattern\":\"[\"}]}")) {
            assertTrue(runCatching { RoleProfileJson.import(json) }.isFailure)
        }
    }
}
