package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.automation.AutomationViewModel
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.data.dao.AutomationDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.core.moduleapi.ToolDispatcher
import com.zero.v2.core.trace.AgentTraceManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Production UI owner, isolated Room and controlled reads; no tool or provider calls. */
class AutomationReadAcceptanceTest {
    private val sample = AutomationEntity("a8-synthetic", "验收任务", "隔离描述", "{}")
    private object NoTools : ToolDispatcher {
        override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult = error("No tools")
        override suspend fun execute(toolCall: ToolCall): FunctionResult = error("No tools")
        override suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult> = error("No tools")
    }

    private suspend fun fixture(read: suspend () -> List<AutomationEntity>, block: suspend (AutomationViewModel) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val store = ViewModelStore()
        val dao = object : AutomationDao by db.automationDao() {
            override suspend fun getActive() = read()
        }
        try {
            val module = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java).automation()
            val vm = withContext(Dispatchers.Main) {
                AutomationViewModel(dao, NoTools, AgentTraceManager(db.agentTraceEventDao()), module).also { store.put("test", it) }
            }
            block(vm)
        } finally { withContext(Dispatchers.Main) { store.clear() }; db.close() }
    }

    private fun readError(vm: AutomationViewModel): String? = vm.state.value.readError

    @Test fun failedReadIsNotSuccessfulEmptyState() = runBlocking {
        fixture({ throw IOException("isolated failure") }) { vm ->
            withTimeout(2000) { vm.state.first { !it.loading } }
            assertNotNull("Failed read must have a distinct error", readError(vm))
        }
    }

    @Test fun failedRefreshKeepsTasksAndRetryRecovers() = runBlocking {
        val calls = AtomicInteger()
        fixture({ if (calls.incrementAndGet() == 2) throw IOException("isolated failure") else listOf(sample) }) { vm ->
            withTimeout(2000) { vm.state.first { !it.loading && it.tasks.isNotEmpty() } }
            withContext(Dispatchers.Main) { vm.refresh() }
            withTimeout(2000) { while (calls.get() < 2 || vm.state.value.loading) delay(10) }
            assertEquals("Failed refresh must preserve loaded tasks", listOf(sample), vm.state.value.tasks)
            assertNotNull(readError(vm))
            withContext(Dispatchers.Main) { vm.refresh() }
            withTimeout(2000) { while (calls.get() < 3 || vm.state.value.loading) delay(10) }
            assertNull(readError(vm))
        }
    }

    @Test fun overlappingRefreshUsesOneQuery() = runBlocking {
        val calls = AtomicInteger(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fixture({ calls.incrementAndGet(); entered.complete(Unit); release.await(); listOf(sample) }) { vm ->
            entered.await()
            withContext(Dispatchers.Main) { repeat(5) { vm.refresh() } }
            delay(100)
            assertEquals("Only one pending query", 1, calls.get())
            release.complete(Unit)
            withTimeout(2000) { vm.state.first { !it.loading && it.tasks.isNotEmpty() } }
        }
    }

    @Test fun canceledReadKeepsDataAndClearsLoading() = runBlocking {
        val calls = AtomicInteger()
        fixture({ if (calls.incrementAndGet() > 1) throw CancellationException("isolated cancellation") else listOf(sample) }) { vm ->
            withTimeout(2000) { vm.state.first { !it.loading && it.tasks.isNotEmpty() } }
            withContext(Dispatchers.Main) { vm.refresh() }
            withTimeout(2000) { while (calls.get() < 2 || vm.state.value.loading) delay(10) }
            assertEquals(listOf(sample), vm.state.value.tasks)
            assertNull(readError(vm))
        }
    }

    @Test fun firstEntryDoesNotReloadButReturningEntryDoes() = runBlocking {
        val calls = AtomicInteger()
        fixture({ calls.incrementAndGet(); listOf(sample) }) { vm ->
            withTimeout(2000) { vm.state.first { !it.loading && it.tasks.isNotEmpty() } }
            withContext(Dispatchers.Main) { vm.onScreenEntered() }
            delay(100)
            assertEquals(1, calls.get())
            withContext(Dispatchers.Main) { vm.onScreenEntered() }
            withTimeout(2000) { while (calls.get() != 2 || vm.state.value.loading) delay(10) }
        }
    }
}
