package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.log.RunLogViewModel
import com.zero.v2.core.data.dao.AgentTraceEventDao
import com.zero.v2.core.data.dao.RecentTraceRow
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.AgentTraceEventEntity
import com.zero.v2.core.trace.AgentTraceManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual owner + isolated Room; never reads/writes personal trace history. */
class RunLogStateAcceptanceTest {
    private suspend fun fixture(read: suspend (Int) -> List<RecentTraceRow>, block: suspend (RunLogViewModel) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        try {
            for (id in listOf("saved", "old", "new")) db.agentTraceEventDao().insert(
                AgentTraceEventEntity(traceId = id, sequence = 1, timestamp = 1, nodeType = "SYSTEM_EVENT", sourceModule = "isolated", summary = id))
            val dao = object : AgentTraceEventDao by db.agentTraceEventDao() {
                override suspend fun getRecentTraces(limit: Int) = read(limit)
            }
            val vm = withContext(Dispatchers.Main) { RunLogViewModel(AgentTraceManager(db.agentTraceEventDao()), dao).also { owner.put("log", it) } }
            block(vm)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }
    private fun row(id: String) = listOf(RecentTraceRow(id, 1, 1))
    private fun error(vm: RunLogViewModel): String? = vm.state.value.javaClass.methods.firstOrNull { it.name == "getHistoryError" }?.invoke(vm.state.value) as? String
    @Test fun failedReloadKeepsKnownHistoryAndReportsFailure() = runBlocking {
        var calls = 0
        fixture({ if (++calls == 1) row("saved") else error("isolated read failure") }) { vm ->
            withTimeout(2000) { while ("saved" !in vm.state.value.historyIds) delay(10) }
            withContext(Dispatchers.Main) { vm.reload() }; delay(200)
            assertTrue("Known history must survive failed reread", "saved" in vm.state.value.historyIds)
            assertNotNull("Failure must not masquerade as empty history", error(vm))
        }
    }
    @Test fun canceledReadDoesNotBecomeSuccessfulEmptyHistory() = runBlocking {
        var calls = 0
        fixture({ if (++calls == 1) row("saved") else throw CancellationException("isolated cancellation") }) { vm ->
            withTimeout(2000) { while ("saved" !in vm.state.value.historyIds) delay(10) }
            withContext(Dispatchers.Main) { vm.reload() }; delay(200)
            assertTrue("Cancellation must preserve history", "saved" in vm.state.value.historyIds)
            assertNull("Cancellation is not a storage failure", error(vm))
        }
    }
    @Test fun supersededReadCannotOverwriteLatestReload() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        try {
            fixture({ if (++calls == 1) { started.complete(Unit); withContext(NonCancellable) { release.await() }; row("old") } else row("new") }) { vm ->
                withTimeout(2000) { started.await() }
                withContext(Dispatchers.Main) { vm.reload() }
                withTimeout(2000) { while ("new" !in vm.state.value.historyIds) delay(10) }
                release.complete(Unit); delay(200)
                assertEquals("Late stale result must not replace latest history", setOf("new"), vm.state.value.historyIds)
            }
        } finally { release.complete(Unit) }
    }
}
