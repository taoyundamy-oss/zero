package com.zero.v2.services.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTriggerQuotaTest {
    @Test fun `simultaneous sources cannot exceed the persisted daily cap`() {
        val state = AtomicReference(1L to 0)
        val quota = DailyTriggerQuota(
            read = { val snapshot = state.get(); Thread.sleep(10); snapshot },
            write = { day, count -> state.set(day to count) },
        )
        val executor = Executors.newFixedThreadPool(20)
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)
        try {
            val results = (1..20).map {
                executor.submit<Boolean> { ready.countDown(); start.await(); quota.acquire(1L, 7) }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(7, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1L to 7, state.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `restart keeps the cap and a new day resets it`() {
        var stored = 1L to 7
        val quota = DailyTriggerQuota({ stored }, { day, count -> stored = day to count })
        assertFalse(quota.acquire(1L, 7))
        assertTrue(quota.acquire(2L, 7))
        assertEquals(2L to 1, stored)
    }
}
