package com.voxengine.util

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ConservativeThrottleTest {

    @Test
    fun `并发请求按间隔依次放行`() = runBlocking {
        val throttle = ConservativeThrottle()
        val intervalMs = 120L
        val passedAt = Collections.synchronizedList(mutableListOf<Long>())
        val jobs = (1..3).map {
            async {
                throttle.waitTurn(intervalMs)
                passedAt.add(System.currentTimeMillis())
            }
        }
        jobs.forEach { it.await() }
        val sorted = passedAt.sorted()
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            assertTrue("相邻放行间隔应约≥${intervalMs}ms，实际=${gap}ms", gap >= intervalMs - 30)
        }
    }

    @Test
    fun `间隔已满足时立即放行`() = runBlocking {
        val throttle = ConservativeThrottle()
        val start = System.currentTimeMillis()
        // 首次调用：lastPassedAt=0，elapsed 远大于 interval，不应等待
        throttle.waitTurn(5_000)
        assertTrue(System.currentTimeMillis() - start < 1_000)
    }

    @Test
    fun `已自然间隔时不再延迟`() = runBlocking {
        val throttle = ConservativeThrottle()
        throttle.waitTurn(100)
        delay(150)
        val start = System.currentTimeMillis()
        throttle.waitTurn(100)
        assertTrue(System.currentTimeMillis() - start < 80)
    }

    @Test
    fun `非正间隔直接放行`() = runBlocking {
        val throttle = ConservativeThrottle()
        val start = System.currentTimeMillis()
        throttle.waitTurn(0)
        throttle.waitTurn(-1)
        assertTrue(System.currentTimeMillis() - start < 100)
    }
}
