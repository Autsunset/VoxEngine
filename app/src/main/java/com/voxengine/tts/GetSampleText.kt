package com.voxengine.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * 系统调用此 Activity 获取示例文本。
 * 当用户在系统设置中点击"试听"时，系统会调用这个。
 */
class GetSampleText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val language = intent?.extras?.getString("language") ?: "zho"

        // 根据语言返回示例文本
        val sampleText = when (language) {
            "zho", "chi", "zh" -> "你好，这是语音合成的示例。"
            else -> "Hello, this is a sample text for speech synthesis."
        }

        val resultIntent = Intent().apply {
            putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sampleText)
        }
        setResult(TextToSpeech.SUCCESS, resultIntent)
        finish()
    }
}
