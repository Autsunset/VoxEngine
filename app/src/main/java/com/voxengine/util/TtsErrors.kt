package com.voxengine.util

/** 合成错误的用户可读文案，供 Reader / 系统 TTS / Test 共用。
 *  已知模式（限流 / API Key）给出解释性文案；其余仅回原始 message，由调用方加自己的上下文前缀。 */
object TtsErrors {
    fun friendly(error: Throwable?): String {
        val message = error?.message.orEmpty()
        return when {
            message.contains("429") -> "合成过快被限流，请调大段落间隔或降低并发条数后再试"
            message.contains("API Key", ignoreCase = true) || message.contains("未配置") -> "API Key 未配置或无效，请在设置中检查"
            else -> message.ifBlank { "未知错误" }
        }
    }
}
