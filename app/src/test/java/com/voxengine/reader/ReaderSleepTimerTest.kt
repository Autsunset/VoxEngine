package com.voxengine.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSleepTimerTest {
    @Test
    fun `disabled timer never expires`() = runTest {
        var expirations = 0
        val timer = ReaderSleepTimer(backgroundScope, { testScheduler.currentTime }) { expirations++ }

        timer.start(0)
        advanceTimeBy(180 * 60_000L)
        runCurrent()

        assertEquals(0L, timer.deadlineElapsedRealtime)
        assertFalse(timer.expireIfDue())
        assertEquals(0, expirations)
    }

    @Test
    fun `timer expires at deadline exactly once`() = runTest {
        var expirations = 0
        val timer = ReaderSleepTimer(backgroundScope, { testScheduler.currentTime }) { expirations++ }

        timer.start(1)
        assertEquals(60_000L, timer.deadlineElapsedRealtime)
        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals(0, expirations)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(1, expirations)
        assertEquals(0L, timer.deadlineElapsedRealtime)
        assertFalse(timer.expireIfDue())
        advanceTimeBy(60_000L)
        assertEquals(1, expirations)
    }

    @Test
    fun `changing duration restarts from now and cancels previous deadline`() = runTest {
        var expirations = 0
        val timer = ReaderSleepTimer(backgroundScope, { testScheduler.currentTime }) { expirations++ }

        timer.start(1)
        advanceTimeBy(30_000L)
        timer.start(2)
        assertEquals(150_000L, timer.deadlineElapsedRealtime)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(0, expirations)
        advanceTimeBy(90_000L)
        runCurrent()
        assertEquals(1, expirations)
    }

    @Test
    fun `shortening duration does not wait for the original deadline`() = runTest {
        var expirations = 0
        val timer = ReaderSleepTimer(backgroundScope, { testScheduler.currentTime }) { expirations++ }

        timer.start(180)
        advanceTimeBy(30_000L)
        timer.start(1)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(1, expirations)
    }

    @Test
    fun `turning off active timer leaves playback running`() = runTest {
        val playback = backgroundScope.launch { delay(Long.MAX_VALUE) }
        val timer = ReaderSleepTimer(backgroundScope, { testScheduler.currentTime }) { playback.cancel() }

        timer.start(1)
        advanceTimeBy(30_000L)
        timer.start(0)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(0L, timer.deadlineElapsedRealtime)
        assertTrue(playback.isActive)
    }

    @Test
    fun `timer can stop stalled playback and its synthesis children`() = runTest {
        val playback = backgroundScope.launch {
            launch { delay(Long.MAX_VALUE) }
            delay(Long.MAX_VALUE)
        }
        val timer = ReaderSleepTimer(backgroundScope, { testScheduler.currentTime }) { playback.cancel() }

        timer.start(1)
        runCurrent()
        val synthesis = playback.children.single()
        advanceTimeBy(60_000L)
        runCurrent()

        assertTrue(playback.isCancelled)
        assertTrue(synthesis.isCancelled)
    }

    @Test
    fun `replacing playback job for a chapter change keeps original timer`() = runTest {
        var playback = backgroundScope.launch { delay(Long.MAX_VALUE) }
        val timer = ReaderSleepTimer(backgroundScope, { testScheduler.currentTime }) { playback.cancel() }

        timer.start(1)
        advanceTimeBy(30_000L)
        playback.cancel()
        playback = backgroundScope.launch { delay(Long.MAX_VALUE) }
        assertEquals(60_000L, timer.deadlineElapsedRealtime)
        advanceTimeBy(30_000L)
        runCurrent()

        assertTrue(playback.isCancelled)
    }

    @Test
    fun `cancelled session cannot stop a later session`() = runTest {
        var expirations = 0
        val timer = ReaderSleepTimer(backgroundScope, { testScheduler.currentTime }) { expirations++ }

        timer.start(1)
        advanceTimeBy(30_000L)
        timer.cancel()
        timer.start(2)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(0, expirations)
        timer.cancel()
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(0, expirations)
    }

    @Test
    fun `service scope cancellation cancels pending timer`() = runTest {
        var expirations = 0
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val timer = ReaderSleepTimer(scope, { testScheduler.currentTime }) { expirations++ }
        timer.start(1)
        runCurrent()
        scope.cancel()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(0, expirations)
    }

    @Test
    fun `elapsed time after device sleep is checked before resuming`() = runTest {
        var now = 100_000L
        var expirations = 0
        val timer = ReaderSleepTimer(backgroundScope, { now }) { expirations++ }

        timer.start(1)
        runCurrent()
        now += 60_000L
        assertTrue(timer.expireIfDue())
        assertFalse(timer.expireIfDue())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, expirations)
    }

    @Test
    fun `timer job rechecks elapsed time after device sleep`() = runTest {
        var now = 100_000L
        var expirations = 0
        val timer = ReaderSleepTimer(backgroundScope, { now }) { expirations++ }

        timer.start(1)
        runCurrent()
        now += 90_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, expirations)
    }

    @Test
    fun `invalid durations are bounded safely`() = runTest {
        val timer = ReaderSleepTimer(backgroundScope, { 5_000L }) {}
        timer.start(-1)
        assertEquals(0L, timer.deadlineElapsedRealtime)
        timer.start(Int.MAX_VALUE)
        assertEquals(5_000L + 180 * 60_000L, timer.deadlineElapsedRealtime)
    }

    @Test
    fun `remaining time never becomes negative and rounds seconds up`() {
        assertEquals(0L, ReaderSleepTimer.remainingMillis(0L, 10L))
        assertEquals(0L, ReaderSleepTimer.remainingMillis(10L, 20L))
        assertEquals(10L, ReaderSleepTimer.remainingMillis(20L, 10L))
        assertEquals("0:00", ReaderSleepTimer.formatRemaining(-1L))
        assertEquals("0:01", ReaderSleepTimer.formatRemaining(1L))
        assertEquals("1:00", ReaderSleepTimer.formatRemaining(59_999L))
        assertEquals("180:00", ReaderSleepTimer.formatRemaining(180 * 60_000L))
    }
}
