package com.voxengine.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * 对可重试的合成错误（网络 IOException）做指数退避重试。
 * CancellationException 直接抛出，不重试。
 * 注：429/5xx 已由 MiMoTTSClient 规范为 IOException（见 MiMoModels.kt），
 * 故此处不再用 message contains "429" 字符串匹配（会误命中任何含 429 的消息，如字节数）。
 */
object RetryPolicy {

    fun isRetryable(error: Throwable): Boolean = error is IOException

    /**
     * @param retryCount 重试次数（总尝试 = 1 + retryCount）
     * @param baseDelayMs 退避基准；第 n 次重试前延迟 baseDelayMs * n^2
     * @param beforeAttempt 每次尝试前回调（含重试），可用于节流；在退避延迟之后执行
     * @param onRetry 决定重试某次失败时回调，参数为「即将开始的重试序号(从1起)」与错误
     */
    suspend fun <T> withRetry(
        retryCount: Int,
        baseDelayMs: Long,
        beforeAttempt: (suspend () -> Unit)? = null,
        onRetry: ((attempt: Int, error: Throwable) -> Unit)? = null,
        block: suspend () -> T
    ): T {
        val maxAttempts = 1 + retryCount.coerceAtLeast(0)
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            try {
                if (attempt > 0) delay(baseDelayMs * attempt * attempt)
                beforeAttempt?.invoke()
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                if (!isRetryable(error) || attempt >= maxAttempts - 1) throw error
                onRetry?.invoke(attempt + 1, error)
                attempt += 1
            }
        }
        throw lastError ?: IllegalStateException("重试失败")
    }
}
