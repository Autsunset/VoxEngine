package com.voxengine.util

/** 合成错误的用户可读文案，供 Reader / 系统 TTS / Test 共用。 */
object TtsErrors {
    fun friendly(error: Throwable?): String {
        val message = error?.message.orEmpty()
        return when {
            message.contains("429") -> "合成过快被限流，请调大段落间隔或降低并发条数后再试"
            message.contains("API Key", ignoreCase = true) || message.contains("未配置") -> "API Key 未配置或无效，请在设置中检查"
            else -> "合成失败: ${message.ifBlank { "未知错误" }}"
        }
    }
}
