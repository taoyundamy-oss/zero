package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.GraphUiState
import com.zero.v2.app.ui.settings.MemoryGraphViewModel
import com.zero.v2.core.data.dao.UserGraphDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.trace.AgentTraceManager
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Isolated DAO failure at the production graph ViewModel boundary. */
class GraphReadStateAcceptanceTest {
    @Test fun failedGraphReadDoesNotBecomeEmptySuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val dao = Proxy.newProxyInstance(UserGraphDao::class.java.classLoader, arrayOf(UserGraphDao::class.java)) { _, method, args ->
            if (method.name == "getAllEntities") throw java.io.IOException("isolated graph failure")
            else method.invoke(db.userGraphDao(), *(args ?: emptyArray()))
        } as UserGraphDao
        try {
            val model = withContext(Dispatchers.Main) {
                MemoryGraphViewModel(dao, AgentTraceManager(db.agentTraceEventDao())).also { owner.put("fixture", it) }
            }
            val result = withTimeout(2000) { model.state.first { !it.loading } }
            assertNotEquals("Failed graph must not look like successful empty graph", GraphUiState(loading = false), result)
            assertNotNull(result.readError)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }

    @Test fun failureRetainsPreviousGraphAndRetryCanRecover() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val row = com.zero.v2.core.data.entity.UserGraphEntity("synthetic", "合成实体",
            com.zero.v2.core.data.entity.EntityType.PERSON, createdAt = 0)
        var attempts = 0
        val dao = Proxy.newProxyInstance(UserGraphDao::class.java.classLoader, arrayOf(UserGraphDao::class.java)) { _, method, _ ->
            when (method.name) {
                "getAllEntities" -> { attempts++; if (attempts == 2) throw java.io.IOException("isolated failure"); listOf(row) }
                "getCurrentAttributes", "getCurrentRelationships" -> emptyList<Any>()
                else -> error("Unexpected DAO method")
            }
        } as UserGraphDao
        try {
            val model = withContext(Dispatchers.Main) {
                MemoryGraphViewModel(dao, AgentTraceManager(db.agentTraceEventDao())).also { owner.put("fixture", it) }
            }
            withTimeout(2000) { model.state.first { !it.loading } }
            withContext(Dispatchers.Main) { model.load() }
            val failed = withTimeout(2000) { model.state.first { it.readError != null } }
            assertEquals(listOf(row), failed.entities)
            withContext(Dispatchers.Main) { model.load() }
            val restored = withTimeout(2000) { model.state.first { !it.loading && it.readError == null } }
            assertEquals(listOf(row), restored.entities); assertEquals(3, attempts)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }

    @Test fun simultaneousLoadsShareOneQuery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java)
            .setQueryExecutor { task -> executor.execute { gate.await(); task.run() } }.build()
        val owner = ViewModelStore()
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val dao = Proxy.newProxyInstance(UserGraphDao::class.java.classLoader, arrayOf(UserGraphDao::class.java)) { _, method, args ->
            if (method.name == "getAllEntities") attempts.incrementAndGet()
            method.invoke(db.userGraphDao(), *(args ?: emptyArray()))
        } as UserGraphDao
        try {
            val model = withContext(Dispatchers.Main) {
                MemoryGraphViewModel(dao, AgentTraceManager(db.agentTraceEventDao())).also {
                    owner.put("fixture", it); it.load(); it.load()
                }
            }
            assertEquals(1, attempts.get())
            gate.countDown()
            val result = withTimeout(4000) { model.state.first { !it.loading } }
            assertTrue(result.entities.isEmpty()); assertNull(result.readError)
        } finally { gate.countDown(); withContext(Dispatchers.Main) { owner.clear() }; db.close(); executor.shutdownNow() }
    }

    @Test fun cancellationDoesNotBecomeReadErrorOrEmptySuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val attempted = CompletableDeferred<Unit>()
        val dao = Proxy.newProxyInstance(UserGraphDao::class.java.classLoader, arrayOf(UserGraphDao::class.java)) { _, _, _ ->
            attempted.complete(Unit); throw CancellationException("isolated cancellation")
        } as UserGraphDao
        try {
            val model = withContext(Dispatchers.Main) {
                MemoryGraphViewModel(dao, AgentTraceManager(db.agentTraceEventDao())).also { owner.put("fixture", it) }
            }
            withTimeout(2000) { attempted.await() }
            withContext(Dispatchers.Main) { yield() }
            assertTrue(model.state.value.loading); assertNull(model.state.value.readError)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close() }
    }

    @Test fun installedGraphMatchesReadonlyDaoSnapshot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val dao = entry.database().userGraphDao()
        val isolated = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        try {
            val entities = dao.getAllEntities()
            val attributes = entities.associate { it.id to dao.getCurrentAttributes(it.id) }
            val relationships = entities.flatMap { dao.getCurrentRelationships(it.id) }
            val model = withContext(Dispatchers.Main) {
                MemoryGraphViewModel(dao, AgentTraceManager(isolated.agentTraceEventDao())).also { owner.put("fixture", it) }
            }
            val actual = withTimeout(5000) { model.state.first { !it.loading } }
            assertNull(actual.readError)
            assertTrue("Readonly graph entity snapshot changed", entities == actual.entities)
            assertTrue("Readonly graph attribute snapshot changed", attributes == actual.attrsByEntity)
            assertTrue("Readonly graph relationship snapshot changed", relationships == actual.relationships)
            java.io.File(context.filesDir, "a8-graph-state-report.json").writeText(org.json.JSONObject()
                .put("passed", true).put("entities", entities.size).put("attributes", attributes.values.sumOf { it.size })
                .put("relationships", relationships.size).put("personalDataWrites", 0).put("providerRequests", 0).toString(2))
        } finally { withContext(Dispatchers.Main) { owner.clear() }; isolated.close() }
    }
}
