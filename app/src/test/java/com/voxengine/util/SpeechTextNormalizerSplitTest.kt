package com.voxengine.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextNormalizerSplitTest {

    @Test
    fun `中文句号切分`() {
        val result = SpeechTextNormalizer.splitSentences("你好。世界。")
        assertEquals(listOf("你好。", "世界。"), result)
    }

    @Test
    fun `英文标点也切`() {
        val result = SpeechTextNormalizer.splitSentences("Hello! How are you? I'm fine.")
        assertEquals(listOf("Hello!", "How are you?", "I'm fine."), result)
    }

    @Test
    fun `无标点整段返回`() {
        val result = SpeechTextNormalizer.splitSentences("这是一段没有句末标点的话")
        assertEquals(listOf("这是一段没有句末标点的话"), result)
    }

    @Test
    fun `跳过纯符号空段`() {
        // 引号包裹的空内容、连续标点不应产出空段
        val result = SpeechTextNormalizer.splitSentences("……。你好！")
        assertEquals(listOf("你好！"), result)
    }

    @Test
    fun `换行也作为分隔`() {
        val result = SpeechTextNormalizer.splitSentences("第一行\n第二行\n")
        assertEquals(listOf("第一行", "第二行"), result)
    }

    @Test
    fun `尾部无标点的剩余段保留`() {
        val result = SpeechTextNormalizer.splitSentences("第一句。第二句没有标点")
        assertEquals(listOf("第一句。", "第二句没有标点"), result)
    }

    @Test
    fun `空文本回退整段`() {
        val result = SpeechTextNormalizer.splitSentences("   ")
        assertEquals(1, result.size)
    }

    @Test
    fun `保留引号与内容`() {
        val result = SpeechTextNormalizer.splitSentences("他说：“你好”。她说再见。")
        // 第一句包含引号及内容，第二句正常切分
        assertEquals(2, result.size)
        assertEquals(true, result[0].contains("你好"))
        assertEquals(true, result[1].contains("她说再见"))
    }
}
