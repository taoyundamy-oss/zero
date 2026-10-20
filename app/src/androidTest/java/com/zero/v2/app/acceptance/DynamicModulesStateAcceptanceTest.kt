package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DynamicModuleActions
import com.zero.v2.app.ui.settings.DynamicModulesViewModel
import com.zero.v2.core.data.dao.DynamicModuleDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.DynamicModuleEntity
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.data.store.ModuleConfigDao
import com.zero.v2.core.trace.AgentTraceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real UI owner + isolated Room/config store; controlled lifecycle, never daily modules. */
class DynamicModulesStateAcceptanceTest {
    private val sample=DynamicModuleEntity("a8-isolated",source="# isolated",createdAt=1,state="ACTIVE",
        configSchema="[{\"key\":\"limit\",\"type\":\"number\",\"default\":5}]")
    private class Actions(val start:suspend()->Boolean={ true },val stop:suspend()->Boolean) : DynamicModuleActions {
        override val discoveredCount=MutableStateFlow(2)
        val starts=AtomicInteger();val stops=AtomicInteger()
        override suspend fun mount(module:DynamicModuleEntity):Boolean { starts.incrementAndGet();return start() }
        override suspend fun unmount(module:DynamicModuleEntity):Boolean { stops.incrementAndGet();return stop() }
    }
    internal suspend fun fixture(actions:DynamicModuleActions,stored:String?=null,missing:Boolean=false,
        modules:Flow<List<DynamicModuleEntity>>?=null,lookup:(suspend(String)->DynamicModuleEntity?)?=null,
        configRead:suspend()->Unit={},block:suspend(DynamicModulesViewModel,ModuleConfigStore)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ZeroDatabase::class.java).build();val store=ViewModelStore()
        val dao=object:DynamicModuleDao by db.dynamicModuleDao() {
            override fun observeAll()=modules ?: MutableStateFlow(if(missing) emptyList() else listOf(sample))
            override suspend fun getById(id:String)=if(lookup!=null)lookup(id) else if(missing) null else sample
        }
        try {
            if(stored!=null)db.moduleConfigDao().upsert(ModuleConfigEntity(moduleId=sample.id,configJson=stored))
            val configDao=object:ModuleConfigDao by db.moduleConfigDao() {
                override suspend fun getByModuleId(moduleId:String):ModuleConfigEntity? { configRead();return db.moduleConfigDao().getByModuleId(moduleId) }
            }
            val configs=ModuleConfigStore(configDao)
            val vm=withContext(Dispatchers.Main) { DynamicModulesViewModel(dao,actions,configs,AgentTraceManager(db.agentTraceEventDao())).also { store.put("test",it) } }
            block(vm,configs)
        } finally { withContext(Dispatchers.Main) { store.clear() };db.close() }
    }
    private fun error(vm:DynamicModulesViewModel,name:String):String? = vm.state.value.javaClass.methods
        .firstOrNull { it.name==name }?.invoke(vm.state.value) as? String
    @Test fun rejectedStopHasVisibleError()=runBlocking {
        val actions=Actions { false }
        fixture(actions) { vm,_ ->
            withContext(Dispatchers.Main) { vm.toggle(sample.id) }
            withTimeout(2000) { while(actions.stops.get()==0 || vm.state.value.busyIds.isNotEmpty())delay(10) }
            assertNotNull("Rejected stop must be visible",error(vm,"getActionError"))
        }
    }
    @Test fun sameFrameToggleReservesOneOperation()=runBlocking {
        val release=CompletableDeferred<Unit>();val actions=Actions { release.await();true }
        try {
            fixture(actions) { vm,_ ->
                withContext(Dispatchers.Main) { repeat(5) { vm.toggle(sample.id) } }
                delay(100);assertEquals("Same-frame taps must issue one operation",1,actions.stops.get())
                release.complete(Unit)
            }
        } finally { release.complete(Unit) }
    }
    @Test fun malformedSavedConfigDoesNotBecomeDefaultValues()=runBlocking {
        fixture(Actions { true },stored="broken-json") { vm,_ ->
            withContext(Dispatchers.Main) { vm.loadDetail(sample.id) };delay(200)
            assertNotNull("Unreadable saved config must not look like valid defaults",error(vm,"getDetailError"))
        }
    }
    @Test fun missingModuleTerminatesWithVisibleDetailError()=runBlocking {
        fixture(Actions { true },missing=true) { vm,_ ->
            withContext(Dispatchers.Main) { vm.loadDetail(sample.id) };delay(100)
            assertNotNull("Missing module must not leave perpetual spinner",error(vm,"getDetailError"))
        }
    }
    @Test fun rejectedStopDuringSaveDoesNotStartAgain()=runBlocking {
        val actions=Actions { false }
        fixture(actions) { vm,_ ->
            withContext(Dispatchers.Main) { vm.saveConfig(sample.id,mapOf("limit" to "7")) }
            withTimeout(2000) { while(actions.stops.get()==0 || vm.state.value.saving)delay(10) };delay(100)
            assertEquals("Rejected stop must not continue into start",0,actions.starts.get())
            assertNotNull(error(vm,"getActionError"))
        }
    }
    @Test fun failedObservationRetainsDataAndRetryRecovers()=runBlocking {
        val attempts=AtomicInteger()
        val source=flow { emit(listOf(sample));if(attempts.incrementAndGet()==1)throw java.io.IOException("isolated") }
        fixture(Actions { true },modules=source) { vm,_ ->
            withTimeout(2000) { while(vm.state.value.loading || vm.state.value.readError==null)delay(10) }
            assertEquals(listOf(sample),vm.state.value.modules)
            withContext(Dispatchers.Main) { vm.retryModules() }
            withTimeout(2000) { while(attempts.get()<2 || vm.state.value.loading)delay(10) }
            assertNull(vm.state.value.readError);assertEquals(listOf(sample),vm.state.value.modules)
        }
    }
    @Test fun cancelledConfigReadDoesNotPublishDefaultsOrError()=runBlocking {
        val calls=AtomicInteger()
        fixture(Actions { true },configRead={ calls.incrementAndGet();throw CancellationException("isolated") }) { vm,_ ->
            withContext(Dispatchers.Main) { vm.loadDetail(sample.id) }
            withTimeout(2000) { while(calls.get()==0 || vm.state.value.detailLoading)delay(10) }
            assertNull(vm.state.value.detail);assertNull(vm.state.value.detailError);assertTrue(vm.state.value.detailValues.isEmpty())
        }
    }
    @Test fun latestDetailWinsOverSlowCancelledRead()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        fixture(Actions { true },lookup={ id ->
            if(id=="old")withContext(NonCancellable) { entered.complete(Unit);release.await() }
            sample.copy(id=id)
        }) { vm,_ ->
            try {
                withContext(Dispatchers.Main) { vm.loadDetail("old") };entered.await()
                withContext(Dispatchers.Main) { vm.loadDetail("new") }
                withTimeout(2000) { while(vm.state.value.detail?.id!="new" || vm.state.value.detailLoading)delay(10) }
                release.complete(Unit);delay(100)
                assertEquals("new",vm.state.value.detail?.id);assertNull(vm.state.value.detailError)
            } finally { release.complete(Unit) }
        }
    }
    @Test fun invalidNumberDoesNotWriteOrRestart()=runBlocking {
        val actions=Actions { true };val old="{\"limit\":5,\"opaque\":\"keep\"}"
        fixture(actions,stored=old) { vm,configs ->
            withContext(Dispatchers.Main) { vm.saveConfig(sample.id,mapOf("limit" to "NaN")) }
            withTimeout(2000) { while(vm.state.value.saving)delay(10) }
            assertNotNull(vm.state.value.actionError);assertEquals(old,configs.get(sample.id))
            assertEquals(0,actions.stops.get());assertEquals(0,actions.starts.get())
        }
    }
    @Test fun successfulSavePreservesUnmanagedConfigAndActivatesOnce()=runBlocking {
        val actions=Actions { true }
        fixture(actions,stored="{\"limit\":5,\"opaque\":\"keep\"}") { vm,configs ->
            withContext(Dispatchers.Main) { vm.saveConfig(sample.id,mapOf("limit" to "7")) }
            withTimeout(2000) { while(vm.state.value.saving || vm.state.value.detailValues["limit"]!="7")delay(10) }
            val saved=org.json.JSONObject(configs.get(sample.id)!!)
            assertEquals(7,saved.getInt("limit"));assertEquals("keep",saved.getString("opaque"))
            assertEquals(1,actions.stops.get());assertEquals(1,actions.starts.get());assertNull(vm.state.value.actionError)
        }
    }
    @Test fun cancelledActivationRetainsSavedConfigAndClearsBusy()=runBlocking {
        val actions=Actions { throw CancellationException("isolated") }
        fixture(actions) { vm,configs ->
            withContext(Dispatchers.Main) { vm.saveConfig(sample.id,mapOf("limit" to "7")) }
            withTimeout(2000) { while(actions.stops.get()==0 || vm.state.value.saving)delay(10) }
            assertEquals(7,org.json.JSONObject(configs.get(sample.id)!!).getInt("limit"))
            assertTrue(vm.state.value.busyIds.isEmpty());assertNull(vm.state.value.actionError)
            assertTrue(vm.state.value.actionMessage?.contains("取消")==true);assertEquals(0,actions.starts.get())
        }
    }
    @Test fun rejectedStartNeverClaimsConfigActivated()=runBlocking {
        val actions=Actions(start={ false }) { true }
        fixture(actions) { vm,_ ->
            withContext(Dispatchers.Main) { vm.saveConfig(sample.id,mapOf("limit" to "7")) }
            withTimeout(2000) { while(actions.starts.get()==0 || vm.state.value.saving)delay(10) }
            assertTrue(vm.state.value.actionError?.contains("启动失败")==true);assertNull(vm.state.value.actionMessage)
        }
    }
}
