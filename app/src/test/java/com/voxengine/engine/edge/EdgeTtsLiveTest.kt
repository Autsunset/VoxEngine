package com.voxengine.engine.edge

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with VOX_EDGE_LIVE_TEST=1; normal unit tests never contact Microsoft's service. */
class EdgeTtsLiveTest {
    @Test
    fun synthesizesChineseAndJapaneseOverTheRealWebSocket() {
        assumeTrue(System.getenv("VOX_EDGE_LIVE_TEST") == "1")
        val client = EdgeTTSClient()
        val samples = listOf(
            "zh-CN-XiaoxiaoNeural" to "你好，这是语音测试。",
            "zh-CN-YunxiNeural" to "微软语音连接测试。",
            "ja-JP-NanamiNeural" to "こんにちは。音声のテストです。"
        )
        samples.forEach { (voice, text) ->
            val mp3 = client.connectAndCollect(text, voice)
            assertTrue("$voice must return MP3 audio, got ${mp3.size} bytes", mp3.size > 1_000)
        }
    }
}
