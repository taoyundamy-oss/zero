package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.app.music.MusicModule
import com.zero.v2.app.ui.music.MusicViewModel
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.data.dao.MusicDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.moduleapi.ToolDispatcher
import com.zero.v2.core.trace.AgentTraceManager
import dagger.hilt.android.EntryPointAccessors
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real UI owner, isolated DAO/dispatcher; never plays audio or edits personal data. */
class MusicStateAcceptanceTest {
    private class Dispatcher(val handler: suspend () -> FunctionResult) : ToolDispatcher {
        override suspend fun execute(name: String, arguments: Map<String, Any?>) = handler()
        override suspend fun execute(toolCall: ToolCall) = handler()
        override suspend fun executeBatch(toolCalls: List<ToolCall>) = toolCalls.map { handler() }
    }

    internal suspend fun fixture(dispatcher: ToolDispatcher, failRead: AtomicBoolean = AtomicBoolean(false), test: suspend (MusicViewModel, AtomicInteger) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val reads = AtomicInteger()
        val dao = Proxy.newProxyInstance(MusicDao::class.java.classLoader, arrayOf(MusicDao::class.java)) { _, method, _ ->
            when (method.name) {
                "loadAllTracks" -> { if (failRead.get()) throw java.io.IOException("isolated read failure"); emptyList<Any>() }
                "loadSchedules" -> emptyList<Any>()
                "deleteSchedule" -> throw java.io.IOException("isolated delete failure")
                "loadPlaylists" -> { reads.incrementAndGet(); emptyList<Any>() }
                else -> error("Unexpected DAO access")
            }
        } as MusicDao
        try {
            val trace = AgentTraceManager(db.agentTraceEventDao())
            val module = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java).music()
            val model = withContext(Dispatchers.Main) {
                MusicViewModel(dao, WorkDirRepository(context, trace), dispatcher, module, trace).also { owner.put("test", it) }
            }
            withTimeout(2000) { model.state.first { !it.loading } }
            test(model, reads)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }

    @Test fun failedPlaybackCommandHasVisibleUiFeedback() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        fixture(Dispatcher { completed.complete(Unit); FunctionResult.failure("isolated denied") }) { model, _ ->
            withContext(Dispatchers.Main) { model.togglePlayPause() }
            completed.await(); delay(100)
            assertEquals("isolated denied", model.state.value.operationError)
            assertFalse(model.state.value.busy)
        }
    }

    @Test fun playlistRefreshWaitsForSuccessfulCommand() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        fixture(Dispatcher { entered.complete(Unit); finish.await(); FunctionResult.success("created") }) { model, reads ->
            withContext(Dispatchers.Main) { model.createPlaylistFrom("synthetic", listOf("synthetic")) }
            entered.await(); delay(100)
            assertEquals("List must not reload before command completes", 1, reads.get())
            finish.complete(Unit)
            withTimeout(2000) { while (reads.get() != 2) delay(10) }
        }
    }

    @Test fun readFailureIsVisibleAndRetryRecovers() = runBlocking {
        val fail = AtomicBoolean(true)
        fixture(Dispatcher { FunctionResult.success("unused") }, fail) { model, reads ->
            assertNotNull(model.state.value.readError)
            assertEquals(0, reads.get())
            fail.set(false)
            withContext(Dispatchers.Main) { model.refresh() }
            withTimeout(2000) { model.state.first { !it.loading && it.readError == null } }
            assertEquals(1, reads.get())
        }
    }

    @Test fun rejectedPlaylistDoesNotRefreshAndCancellationDoesNotBecomeFailure() = runBlocking {
        fixture(Dispatcher { FunctionResult.failure("denied") }) { model, reads ->
            withContext(Dispatchers.Main) { model.createPlaylistFrom("synthetic", emptyList()) }
            withTimeout(2000) { model.state.first { it.operationError != null } }
            assertEquals(1, reads.get())
        }
        fixture(Dispatcher { throw CancellationException("isolated cancellation") }) { model, reads ->
            withContext(Dispatchers.Main) { model.togglePlayPause() }
            delay(100)
            assertNull(model.state.value.operationError)
            assertFalse(model.state.value.busy)
            assertEquals(1, reads.get())
        }
    }

    @Test fun terminalScheduleDeleteFailureIsVisibleAndDoesNotReload() = runBlocking {
        fixture(Dispatcher { error("Must not invoke tool for local terminal cleanup") }) { model, reads ->
            withContext(Dispatchers.Main) { model.removeSchedule("synthetic") }
            withTimeout(2000) { model.state.first { it.operationError != null && !it.busy } }
            assertEquals("无法清除定时记录，请重试。", model.state.value.operationError)
            assertEquals(1, reads.get())
            assertFalse(model.state.value.busy)
        }
    }
}
