package com.zero.v2.services.agent

/** Monotonic deadline shared across loop restarts; scheduler kicks cannot shorten it. */
internal class AutonomousTickCadence {
    private companion object { const val MINIMUM_INTERVAL_MS = 10_000L }
    private var nextAt = 0L

    @Synchronized fun isDue(now: Long): Boolean = now >= nextAt

    @Synchronized fun schedule(now: Long, intervalMs: Long) {
        nextAt = now + intervalMs.coerceAtLeast(MINIMUM_INTERVAL_MS)
    }

    @Synchronized fun remaining(now: Long): Long = (nextAt - now).coerceAtLeast(1L)

    @Synchronized fun restore(now: Long, remainingMs: Long) {
        nextAt = now + remainingMs.coerceAtLeast(0L)
    }

    @Synchronized fun outstanding(now: Long): Long = (nextAt - now).coerceAtLeast(0L)
}
