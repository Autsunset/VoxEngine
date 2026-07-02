package com.voxengine.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 克隆/设计音色合成请求的节流器：保证相邻两次放行至少间隔 intervalMs。
 * 读时间戳、退避等待、更新时间戳在同一临界区完成，并发请求排队依次放行，
 * 不会出现两个请求同时通过检查。
 * @param now 时钟注入点，便于单测。
 */
class ConservativeThrottle(private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var lastPassedAt = 0L

    suspend fun waitTurn(intervalMs: Long) {
        if (intervalMs <= 0) return
        mutex.withLock {
            val elapsed = now() - lastPassedAt
            if (elapsed in 0 until intervalMs) delay(intervalMs - elapsed)
            lastPassedAt = now()
        }
    }
}
