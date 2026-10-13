package com.voxengine.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Session timer, owned by the service and confined to its main dispatcher. */
class ReaderSleepTimer(
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    private val onExpired: () -> Unit
) {
    var deadlineElapsedRealtime: Long = 0L
        private set

    private var job: Job? = null

    fun start(minutes: Int) {
        cancel()
        val duration = minutes.coerceIn(0, MAX_MINUTES)
        if (duration == 0) return
        deadlineElapsedRealtime = elapsedRealtime() + duration * 60_000L
        job = scope.launch {
            // Recheck a monotonic clock after device sleep instead of counting ticks.
            while (!expireIfDue()) {
                delay(remainingMillis(deadlineElapsedRealtime, elapsedRealtime()).coerceAtMost(1_000L))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        deadlineElapsedRealtime = 0L
    }

    fun expireIfDue(): Boolean {
        if (deadlineElapsedRealtime == 0L || elapsedRealtime() < deadlineElapsedRealtime) return false
        cancel()
        onExpired()
        return true
    }

    companion object {
        const val MAX_MINUTES = 180

        fun remainingMillis(deadlineElapsedRealtime: Long, now: Long): Long =
            if (deadlineElapsedRealtime == 0L) 0L else (deadlineElapsedRealtime - now).coerceAtLeast(0L)

        fun formatRemaining(remainingMillis: Long): String {
            val seconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1_000L
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }
}
