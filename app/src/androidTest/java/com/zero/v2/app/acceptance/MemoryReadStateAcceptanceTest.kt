package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.MemoryManageViewModel
import com.zero.v2.app.ui.settings.MemoryUiState
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.repository.MemoryDeletionRepository
import com.zero.v2.core.trace.AgentTraceManager
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.zero.v2.core.data.entity.MemoryEntity
import org.junit.Assert.*
import org.junit.Test

/** Real ViewModel at its DAO stream boundary; isolated Room, no personal data or provider requests. */
class MemoryReadStateAcceptanceTest {
    @Test fun failedReadDoesNotLookLikeEmptySuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val attempted = CompletableDeferred<Unit>()
        val dao = Proxy.newProxyInstance(MemoryDao::class.java.classLoader, arrayOf(MemoryDao::class.java)) { _, method, args ->
            if (method.name == "observeAll") flow<List<com.zero.v2.core.data.entity.MemoryEntity>> {
                attempted.complete(Unit)
                throw java.io.IOException("isolated read failure")
            } else method.invoke(db.memoryDao(), *(args ?: emptyArray()))
        } as MemoryDao
        try {
            val model = withContext(Dispatchers.Main) {
                MemoryManageViewModel(dao, MemoryDeletionRepository(db.memoryExtractionJobDao()),
                    AgentTraceManager(db.agentTraceEventDao())).also { owner.put("fixture", it) }
            }
            withTimeout(2000) { attempted.await() }
            withContext(Dispatchers.Main) { yield() }
            assertNotEquals("Read failure must not be indistinguishable from empty success", MemoryUiState(), model.state.value)
            assertNotNull(model.state.value.readError)
            assertFalse(model.state.value.isLoading)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }
    @Test fun retryKeepsLastDataAndFiltersAndDoesNotDuplicateObserver() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val row = MemoryEntity("isolated", "合成记忆")
        var attempts = 0
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val dao = intercepted(db.memoryDao()) {
            attempts++
            if (attempts == 1) flow { emit(listOf(row)); throw java.io.IOException("isolated later failure") }
            else flow {
                try { release.await(); emit(listOf(row)); awaitCancellation() }
                finally { cancelled.complete(Unit) }
            }
        }
        try {
            val model = withContext(Dispatchers.Main) {
                MemoryManageViewModel(dao, MemoryDeletionRepository(db.memoryExtractionJobDao()),
                    AgentTraceManager(db.agentTraceEventDao())).also { owner.put("fixture", it) }
            }
            withTimeout(2000) { model.state.first { it.readError != null } }
            withContext(Dispatchers.Main) {
                model.setQuery("合成"); model.setTypeFilter(com.zero.v2.core.common.MemoryType.EVENT)
                model.retryRead(); model.retryRead(); model.retryRead()
            }
            assertEquals(2, attempts)
            assertEquals(listOf(row), model.state.value.all)
            assertEquals("合成", model.state.value.query)
            assertEquals(com.zero.v2.core.common.MemoryType.EVENT, model.state.value.typeFilter)
            assertTrue(model.state.value.isLoading); assertNull(model.state.value.readError)
            release.complete(Unit)
            withTimeout(2000) { model.state.first { !it.isLoading && it.readError == null } }
            withContext(Dispatchers.Main) { owner.clear() }
            withTimeout(2000) { cancelled.await() }
            assertNull(model.state.value.readError)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }

    @Test fun synchronousDaoFailureCanRetryToGenuineEmptyResult() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        var attempts = 0
        val dao = intercepted(db.memoryDao()) {
            attempts++
            if (attempts == 1) throw IllegalStateException("isolated subscription failure")
            flow { emit(emptyList<MemoryEntity>()); awaitCancellation() }
        }
        try {
            val model = withContext(Dispatchers.Main) {
                MemoryManageViewModel(dao, MemoryDeletionRepository(db.memoryExtractionJobDao()),
                    AgentTraceManager(db.agentTraceEventDao())).also { owner.put("fixture", it) }
            }
            withTimeout(2000) { model.state.first { it.readError != null } }
            withContext(Dispatchers.Main) { model.retryRead() }
            val state = withTimeout(2000) { model.state.first { !it.isLoading && it.readError == null } }
            assertTrue(state.all.isEmpty()); assertEquals(2, attempts)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }

    @Test fun upstreamCancellationPropagatesWithoutReadFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val failed = CompletableDeferred<Unit>()
        val dao = intercepted(db.memoryDao()) { flow {
            try { throw CancellationException("isolated lifecycle cancellation") }
            finally { failed.complete(Unit) }
        } }
        try {
            val model = withContext(Dispatchers.Main) {
                MemoryManageViewModel(dao, MemoryDeletionRepository(db.memoryExtractionJobDao()),
                    AgentTraceManager(db.agentTraceEventDao())).also { owner.put("fixture", it) }
            }
            withTimeout(2000) { failed.await() }
            withContext(Dispatchers.Main) { yield() }
            assertNull(model.state.value.readError)
            assertTrue(model.state.value.isLoading)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }

    @Test fun installedRoomReadAndSourceClassificationMatchActualViewModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val owner = ViewModelStore()
        val db = entry.database()
        try {
            val expected = withTimeout(5000) { db.memoryDao().observeAll().first() }
            val model = withContext(Dispatchers.Main) {
                MemoryManageViewModel(db.memoryDao(), MemoryDeletionRepository(db.memoryExtractionJobDao()),
                    entry.traces()).also { owner.put("fixture", it) }
            }
            val actual = withTimeout(5000) { model.state.first { !it.isLoading } }
            assertNull(actual.readError)
            assertEquals(expected.size, actual.all.size)
            expected.sortedBy { it.id }.zip(actual.all.sortedBy { it.id }).forEach { (before, after) ->
                assertTrue("Readonly record snapshot changed", before.copy(embedding = null) == after.copy(embedding = null))
                assertTrue("Readonly vector snapshot changed", when {
                    before.embedding == null -> after.embedding == null
                    after.embedding == null -> false
                    else -> before.embedding.contentEquals(after.embedding)
                })
            }
            val verified = actual.all.count { com.zero.v2.core.data.entity.GraphMemoryEvidence.eligible(it) }
            java.io.File(context.filesDir, "a8-memory-state-report.json").writeText(org.json.JSONObject()
                .put("passed", true).put("realRoomAndViewModel", true).put("recordCount", actual.all.size)
                .put("sourceEligibleCount", verified).put("unverifiedCount", actual.all.size - verified)
                .put("personalDataWrites", 0).put("providerRequests", 0).toString(2))
        } finally { withContext(Dispatchers.Main) { owner.clear() } }
    }

    private fun intercepted(delegate: MemoryDao, stream: () -> Flow<List<MemoryEntity>>): MemoryDao =
        Proxy.newProxyInstance(MemoryDao::class.java.classLoader, arrayOf(MemoryDao::class.java)) { _, method, args ->
            if (method.name == "observeAll") stream() else method.invoke(delegate, *(args ?: emptyArray()))
        } as MemoryDao

}
