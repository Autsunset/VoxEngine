package com.voxengine.util

import org.junit.Assert.assertTrue
import org.junit.Test

class TtsErrorsTest {
    @Test
    fun friendlyExplainsRateLimit() {
        val message = TtsErrors.friendly(IllegalStateException("HTTP 429 Too Many Requests"))

        assertTrue(message.contains("限流"))
    }

    @Test
    fun friendlyExplainsMissingApiKey() {
        val message = TtsErrors.friendly(IllegalStateException("MiMo API Key 未配置"))

        assertTrue(message.contains("API Key"))
    }

    @Test
    fun friendlyFallsBackForUnknownError() {
        val message = TtsErrors.friendly(IllegalStateException("boom"))

        assertTrue(message.contains("boom"))
    }
}
