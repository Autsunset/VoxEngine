package com.voxengine.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * 系统调用此 Activity 检查 TTS 引擎数据是否完整。
 * 正常情况下不需要安装额外数据，所以直接返回成功。
 */
class CheckVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val resultIntent = Intent().apply {
            // 返回数据完整的结果
            putExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, arrayListOf("zho"))
            putExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf<String>())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, resultIntent)
        finish()
    }
}
