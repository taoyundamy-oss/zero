package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentJob
import com.zero.v2.services.agent.Scheduler
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SchedulerCancellationAcceptanceTest {
    private fun job(id: String, action: suspend () -> Unit) = object : AgentJob {
        override val id = id
        override val nextRunTime = 0L
        override suspend fun execute() = action()
    }

    private suspend fun fixture(
        jobs: Set<AgentJob>,
        block: suspend (Scheduler, SystemBusImpl, AgentTraceManager) -> Unit,
    ) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        val bus = SystemBusImpl()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val scheduler = Scheduler(bus, jobs, trace, javax.inject.Provider { error("Synthetic non-module jobs must not resolve the lifecycle guard") })
        try { scheduler.onAwake(); scheduler.onStart(); block(scheduler, bus, trace) }
        finally { scheduler.onDestroy(); delay(100); db.close() }
    }

    @Test fun destroyingSchedulerDoesNotDispatchRemainingJobs() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val remaining = AtomicInteger()
        fixture(linkedSetOf(
            job("waiting") { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } },
            job("remaining") { remaining.incrementAndGet() },
        )) { scheduler, bus, trace ->
            bus.onTick.emit(System.currentTimeMillis())
            withTimeout(3000) { entered.await() }
            scheduler.onDestroy()
            withTimeout(3000) { cancelled.await() }
            delay(150)
            assertEquals("Destroy must cancel the entire tick before another task runs", 0, remaining.get())
            assertFalse(trace.getTrace("scheduler").any { it.summary.startsWith("job_failed: id=waiting") })
        }
    }

    @Test fun localJobCancellationDoesNotKillSubscriptionOrCountAsFailure() = runBlocking {
        val attempts = AtomicInteger()
        val remaining = AtomicInteger()
        fixture(linkedSetOf(
            job("local_cancel") { if (attempts.incrementAndGet() == 1) throw CancellationException("local tool cancelled") },
            job("remaining") { remaining.incrementAndGet() },
        )) { _, bus, trace ->
            bus.onTick.emit(System.currentTimeMillis())
            withTimeout(3000) { while (remaining.get() < 1) delay(10) }
            assertFalse("A local cancellation is not a module crash", trace.getTrace("scheduler").any { it.summary.startsWith("job_failed: id=local_cancel") })
            assertTrue(trace.getTrace("scheduler").any { it.summary.startsWith("job_cancelled: id=local_cancel") })
            bus.onTick.emit(System.currentTimeMillis())
            withTimeout(3000) { while (remaining.get() < 2) delay(10) }
            assertEquals(2, attempts.get())
            assertTrue(trace.getTrace("scheduler").any { it.summary.startsWith("job_executed: id=local_cancel") })
        }
    }

    @Test fun ordinaryFailureStillAllowsOtherJobsAndFutureTicks() = runBlocking {
        val remaining = AtomicInteger()
        fixture(linkedSetOf(job("broken") { error("isolated failure") }, job("remaining") { remaining.incrementAndGet() })) { _, bus, trace ->
            repeat(2) { iteration ->
                bus.onTick.emit(System.currentTimeMillis())
                withTimeout(3000) { while (remaining.get() <= iteration) delay(10) }
            }
            assertEquals(2, trace.getTrace("scheduler").count { it.summary.startsWith("job_failed: id=broken") })
        }
    }
}
