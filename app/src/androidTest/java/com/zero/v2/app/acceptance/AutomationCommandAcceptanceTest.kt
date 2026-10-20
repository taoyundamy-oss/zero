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
import java.util.concurrent.atomic.AtomicInteger
import java.io.IOException

/** Production ViewModel/typed commands, isolated Room and dispatcher; no personal tasks or network. */
class AutomationCommandAcceptanceTest {
    private class Dispatcher(val handler: suspend (String) -> FunctionResult) : ToolDispatcher {
        override suspend fun execute(name: String, arguments: Map<String,Any?>) = handler(name)
        override suspend fun execute(toolCall: ToolCall) = error("UI uses typed commands")
        override suspend fun executeBatch(toolCalls: List<ToolCall>) = error("UI uses typed commands")
    }
    internal suspend fun fixture(handler: suspend (String) -> FunctionResult, tasks: List<AutomationEntity> = emptyList(), block: suspend (AutomationViewModel, AtomicInteger) -> Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ZeroDatabase::class.java).build()
        val store=ViewModelStore();val reads=AtomicInteger()
        val dao=object:AutomationDao by db.automationDao() {
            override suspend fun getActive():List<AutomationEntity> { reads.incrementAndGet();return tasks }
        }
        try {
            val module=EntryPointAccessors.fromApplication(context,RegenerationAcceptanceEntryPoint::class.java).automation()
            val vm=withContext(Dispatchers.Main) {
                AutomationViewModel(dao,Dispatcher(handler),AgentTraceManager(db.agentTraceEventDao()),module).also { store.put("test",it) }
            }
            withTimeout(2000) { vm.state.first { !it.loading } }
            block(vm,reads)
        } finally { withContext(Dispatchers.Main) { store.clear() };db.close() }
    }
    @Test fun rejectedManagementAndEditAreVisibleWithoutFalseRefresh()=runBlocking {
        for(edit in listOf(false,true)) {
            val done=CompletableDeferred<Unit>()
            fixture({ done.complete(Unit);FunctionResult.failure("isolated denied") }) { vm,reads ->
                withContext(Dispatchers.Main) { if(edit) vm.updateSchedule("synthetic","MANUAL","",null) else vm.manage("synthetic","pause") }
                done.await();delay(100)
                assertTrue("Rejected action must be visible",vm.state.value.message?.contains("isolated denied")==true)
                assertEquals("Rejected action must not report a refreshed success",1,reads.get())
            }
        }
    }
    @Test fun rejectedCreationKeepsDraftAndDoesNotNavigate()=runBlocking {
        fixture({ if(it=="draft_automation") FunctionResult.success("{\"title\":\"隔离草稿\",\"schedule_type\":\"MANUAL\"}") else FunctionResult.failure("isolated denied") }) { vm,reads ->
            withContext(Dispatchers.Main) { vm.generateDraft("隔离需求") }
            withTimeout(2000) { vm.draft.first { it!=null } }
            val draft=vm.draft.value;val returns=AtomicInteger()
            withContext(Dispatchers.Main) { vm.confirmCreateDraft { returns.incrementAndGet() } }
            withTimeout(2000) { vm.state.first { it.message!=null && !it.creating } }
            assertEquals("Failed creation must retain edited draft",draft,vm.draft.value)
            assertEquals(0,returns.get());assertEquals(1,reads.get())
        }
    }
    @Test fun cancelledDraftClearsBusyWithoutFailure()=runBlocking {
        val entered=CompletableDeferred<Unit>()
        fixture({ entered.complete(Unit);throw CancellationException("isolated cancellation") }) { vm,_ ->
            withContext(Dispatchers.Main) { vm.generateDraft("隔离需求") }
            entered.await();delay(100)
            assertFalse("Cancelled draft must clear busy",vm.drafting.value)
            assertNull(vm.state.value.message)
        }
    }
    @Test fun cancelledRunClearsBusyWithoutRefresh()=runBlocking {
        val entered=CompletableDeferred<Unit>()
        fixture({ entered.complete(Unit);throw CancellationException("isolated cancellation") }) { vm,reads ->
            withContext(Dispatchers.Main) { vm.runNow("synthetic") }
            entered.await();delay(100)
            assertNull("Cancelled run must clear running state",vm.state.value.runningId)
            assertNull(vm.state.value.message);assertEquals(1,reads.get())
        }
    }

    @Test fun ordinaryCommandExceptionsBecomeVisibleAndReleaseBusy()=runBlocking {
        for(action in listOf("draft","run","manage","update")) {
            val done=CompletableDeferred<Unit>()
            fixture({ done.complete(Unit);throw IOException("isolated exception") }) { vm,reads ->
                withContext(Dispatchers.Main) {
                    when(action) {
                        "draft" -> vm.generateDraft("隔离需求")
                        "run" -> vm.runNow("synthetic")
                        "manage" -> vm.manage("synthetic","pause")
                        else -> vm.updateSchedule("synthetic","MANUAL","",null)
                    }
                }
                done.await()
                withTimeout(2000) { vm.state.first { it.messageIsError && it.message!=null && it.runningId==null } }
                withTimeout(2000) { vm.drafting.first { !it } }
                assertEquals(1,reads.get())
            }
        }
    }

    @Test fun cancelledCreationKeepsDraftAndClearsBusy()=runBlocking {
        val entered=CompletableDeferred<Unit>()
        fixture({ if(it=="draft_automation") FunctionResult.success("{\"title\":\"隔离草稿\"}")
            else { entered.complete(Unit);throw CancellationException("isolated cancellation") } }) { vm,reads ->
            withContext(Dispatchers.Main) { vm.generateDraft("隔离需求") }
            withTimeout(2000) { vm.draft.first { it!=null } }
            withTimeout(2000) { vm.drafting.first { !it } }
            val draft=vm.draft.value;val returns=AtomicInteger()
            withContext(Dispatchers.Main) { vm.confirmCreateDraft { returns.incrementAndGet() } }
            entered.await()
            withTimeout(2000) { vm.state.first { !it.creating } }
            assertEquals(draft,vm.draft.value);assertEquals(0,returns.get());assertEquals(1,reads.get())
            assertFalse(vm.state.value.messageIsError)
        }
    }

    @Test fun successfulCreationWaitsThenRefreshesAndNavigatesOnce()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        fixture({ if(it=="draft_automation") FunctionResult.success("{\"title\":\"隔离草稿\"}")
            else { entered.complete(Unit);release.await();FunctionResult.success("created") } }) { vm,reads ->
            withContext(Dispatchers.Main) { vm.generateDraft("隔离需求") }
            withTimeout(2000) { vm.draft.first { it!=null } }
            val returns=AtomicInteger()
            withContext(Dispatchers.Main) { vm.confirmCreateDraft { returns.incrementAndGet() };vm.confirmCreateDraft { returns.incrementAndGet() } }
            entered.await();delay(100)
            assertTrue(vm.state.value.creating);assertNotNull(vm.draft.value)
            assertEquals(0,returns.get());assertEquals(1,reads.get())
            release.complete(Unit)
            withTimeout(2000) { while(reads.get()!=2 || returns.get()!=1 || vm.state.value.creating) delay(10) }
            assertNull(vm.draft.value);assertFalse(vm.state.value.messageIsError)
        }
    }
}
