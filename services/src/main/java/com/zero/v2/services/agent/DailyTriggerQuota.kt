package com.zero.v2.services.agent

/** Owns the read/check/write operation for the persisted daily trigger counter. */
internal class DailyTriggerQuota(
    private val read: () -> Pair<Long, Int>,
    private val write: (Long, Int) -> Unit,
) {
    @Synchronized
    fun acquire(day: Long, limit: Int): Boolean {
        val (storedDay, storedCount) = read()
        val count = if (storedDay == day) storedCount else 0
        if (count >= limit) return false
        write(day, count + 1)
        return true
    }
}
