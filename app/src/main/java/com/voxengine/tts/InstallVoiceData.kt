package com.voxengine.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * 系统调用此 Activity 安装语音数据。
 * VoxEngine 不需要安装额外数据，所以直接跳转到主界面配置。
 */
class InstallVoiceData : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 跳转到主界面让用户配置 API Key
        val mainIntent = Intent().apply {
            setClassName(packageName, "com.voxengine.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(mainIntent)
        finish()
    }
}
