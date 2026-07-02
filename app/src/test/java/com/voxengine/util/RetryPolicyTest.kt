package com.voxengine.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {

    @Test
    fun retriesIoExceptionThenSucceeds() {
        var attempts = 0
        val result = runBlocking {
            RetryPolicy.withRetry(retryCount = 3, baseDelayMs = 1) {
                attempts++
                if (attempts < 3) throw IOException("transient")
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun doesNotRetryNonRetryableError() {
        var attempts = 0
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                RetryPolicy.withRetry(retryCount = 5, baseDelayMs = 1) {
                    attempts++
                    throw IllegalStateException("fatal")
                }
            }
        }
        assertEquals(1, attempts)
        assertTrue(error.message!!.contains("fatal"))
    }

    @Test
    fun stopsAfterMaxAttempts() {
        var attempts = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                RetryPolicy.withRetry(retryCount = 2, baseDelayMs = 1) {
                    attempts++
                    throw IOException("always")
                }
            }
        }
        assertEquals(3, attempts) // 1 initial + 2 retries
    }

    @Test
    fun doesNotRetryCancellation() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                RetryPolicy.withRetry(retryCount = 5, baseDelayMs = 1) {
                    attempts++
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun doesNotMatch429InMessageString() {
        // 429/5xx 已由 MiMoTTSClient 规范为 IOException；isRetryable 不再靠 message contains "429" 判断，
        // 否则任何含 "429" 的消息（如字节数 "got 4290 bytes"）都会被误判为可重试。
        var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                RetryPolicy.withRetry(retryCount = 3, baseDelayMs = 1) {
                    attempts++
                    throw IllegalStateException("got 4290 bytes")
                }
            }
        }
        assertEquals(1, attempts)
    }
}
