package com.zero.v2.core.data.store

import com.zero.v2.core.data.entity.ModuleConfigEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

class ModuleConfigStoreTest {
    private class Dao : ModuleConfigDao {
        val rows = mutableMapOf<String, ModuleConfigEntity>()
        val writes = Channel<String>(Channel.UNLIMITED)
        override suspend fun getByModuleId(moduleId: String) = rows[moduleId]
        override fun observeAll() = flowOf(rows.values.toList())
        override suspend fun upsert(entity: ModuleConfigEntity) {
            rows[entity.moduleId] = entity
            writes.send(entity.moduleId)
        }
        override suspend fun delete(moduleId: String) { rows.remove(moduleId) }
    }

    @Test fun `burst keeps every module and immediate save wins over pending old value`() = runBlocking {
        val job = SupervisorJob()
        val dao = Dao()
        val store = ModuleConfigStore(dao, CoroutineScope(job + Dispatchers.Unconfined))
        try {
            store.updateConfigDebounced("a", "old")
            store.updateConfigDebounced("b", "keep-b")
            store.updateConfigDebounced("c", "keep-c")
            store.saveNow("a", "new")
            withTimeout(3000) { repeat(3) { dao.writes.receive() } }
            assertEquals("new", dao.rows["a"]?.configJson)
            assertEquals("keep-b", dao.rows["b"]?.configJson)
            assertEquals("keep-c", dao.rows["c"]?.configJson)
        } finally { job.cancel() }
    }

    @Test fun `rejected confirmed candidate is never replayed when storage recovers`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val real=Dao();var reject=true
        val wrapped=object:ModuleConfigDao by real {
            override suspend fun upsert(entity:ModuleConfigEntity) {
                if(reject) error("isolated reject")
                real.upsert(entity)
            }
        }
        val store=ModuleConfigStore(wrapped,scope)
        try {
            real.upsert(ModuleConfigEntity(moduleId="a",configJson="old"))
            try { store.saveConfirmed("a","rejected");fail("must reject") } catch(_:IllegalStateException) { }
            reject=false;delay(700)
            assertEquals("old",real.rows["a"]?.configJson)
        } finally { scope.cancel() }
    }
    @Test fun `failed confirmed candidate preserves accepted pending requests across modules`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val real=Dao()
        val store=ModuleConfigStore(object:ModuleConfigDao by real {
            override suspend fun upsert(entity:ModuleConfigEntity) {
                if(entity.configJson=="rejected") error("isolated reject")
                real.upsert(entity)
            }
        },scope)
        try {
            store.updateConfigDebounced("a","accepted");store.updateConfigDebounced("b","keep-b")
            try { store.saveConfirmed("a","rejected");fail("must reject") } catch(_:IllegalStateException) { }
            withTimeout(3000) { while(real.rows["a"]?.configJson!="accepted" || real.rows["b"]?.configJson!="keep-b") delay(10) }
        } finally { scope.cancel() }
    }
    @Test fun `newer pending request during confirmed write survives success or failure`() = runBlocking {
        for(reject in listOf(false,true)) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val real=Dao()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val store=ModuleConfigStore(object:ModuleConfigDao by real {
                override suspend fun upsert(entity:ModuleConfigEntity) {
                    if(entity.configJson=="manual") { entered.complete(Unit);release.await();if(reject) error("isolated reject") }
                    real.upsert(entity)
                }
            },scope)
            try {
                val manual=async { runCatching { store.saveConfirmed("a","manual") } }
                entered.await();store.updateConfigDebounced("a","newer");store.updateConfigDebounced("b","keep-b")
                release.complete(Unit);assertEquals(!reject,manual.await().isSuccess)
                withTimeout(3000) { while(real.rows["a"]?.configJson!="newer" || real.rows["b"]?.configJson!="keep-b") delay(10) }
            } finally { release.complete(Unit);scope.cancel() }
        }
    }
    @Test fun `confirmed success retires older pending candidate`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val dao=Dao();val store=ModuleConfigStore(dao,scope)
        try {
            store.updateConfigDebounced("a","old");store.updateConfigDebounced("b","keep-b")
            store.saveConfirmed("a","confirmed")
            withTimeout(3000) { while(dao.rows["b"]?.configJson!="keep-b") delay(10) }
            assertEquals("confirmed",dao.rows["a"]?.configJson)
        } finally { scope.cancel() }
    }
    @Test fun `cancelled confirmed attempt preserves previous accepted queue`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val real=Dao()
        val store=ModuleConfigStore(object:ModuleConfigDao by real {
            override suspend fun upsert(entity:ModuleConfigEntity) {
                if(entity.configJson=="cancelled") awaitCancellation()
                real.upsert(entity)
            }
        },scope)
        try {
            store.updateConfigDebounced("a","accepted")
            var cancelled=false
            try { withTimeout(20) { store.saveConfirmed("a","cancelled") } } catch(_:CancellationException) { cancelled=true }
            assertTrue(cancelled)
            withTimeout(3000) { while(real.rows["a"]?.configJson!="accepted") delay(10) }
        } finally { scope.cancel() }
    }
    @Test fun `original immediate save still retries its accepted value after failure`() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val real=Dao();var reject=true
        val store=ModuleConfigStore(object:ModuleConfigDao by real {
            override suspend fun upsert(entity:ModuleConfigEntity) {
                if(reject) error("isolated unavailable")
                real.upsert(entity)
            }
        },scope)
        try {
            try { store.saveNow("a","accepted");fail("must fail immediate attempt") } catch(_:IllegalStateException) { }
            reject=false
            withTimeout(3000) { while(real.rows["a"]?.configJson!="accepted") delay(10) }
        } finally { scope.cancel() }
    }
}
