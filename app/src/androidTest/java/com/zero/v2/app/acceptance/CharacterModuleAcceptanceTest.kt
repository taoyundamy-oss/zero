package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.ModuleCharacterStateActions
import com.zero.v2.app.ui.settings.CharacterManageViewModel
import androidx.lifecycle.ViewModelStore
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.dao.CharacterStateDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.data.store.*
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.chat.CharacterStateModule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual module, lifecycle.safeCall, SQLite/config retry worker; no personal module configuration. */
class CharacterModuleAcceptanceTest {
    private lateinit var restoreFreshModule: suspend () -> CharacterStateModule
    private suspend fun fixture(wrap: (CharacterStateDao)->CharacterStateDao = {it}, configWrap: (ModuleConfigDao)->ModuleConfigDao = {it}, block:suspend (CharacterStateModule, ModuleCharacterStateActions, ZeroDatabase)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ZeroDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val store=acceptanceConfigStore(configWrap(db.moduleConfigDao()),scope)
        val bus=ChatBusImpl();val trace=AgentTraceManager(db.agentTraceEventDao())
        val module=CharacterStateModule(wrap(db.characterStateDao()),db.characterDao(),bus,store,trace)
        val catalog=object:FunctionCatalog {
            override fun getAllDefinitions()=emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId:String)=emptyList<FunctionEntry>()
        }
        val registrar=object:ToolRegistrar {
            override fun register(definition:FunctionDefinition,sourceModuleId:String,permission:ActionPermission,handler:suspend (Map<String,Any?>)->FunctionResult)=Unit
            override fun unregister(name:String)=Unit
            override fun registerContributions(module:IZeroModule)=0
        }
        val lifecycle=ModuleLifecycleManager(setOf(module),bus,store,trace,catalog,registrar)
        val lifecycles=mutableListOf(lifecycle)
        restoreFreshModule = {
            val restored=CharacterStateModule(db.characterStateDao(),db.characterDao(),bus,store,trace)
            val manager=ModuleLifecycleManager(setOf(restored),bus,store,trace,catalog,registrar)
            lifecycles.add(manager);manager.initializeAll();restored
        }
        try {
            db.moduleConfigDao().upsert(ModuleConfigEntity(moduleId="character_state",configJson="{\"dynamicStyleEnabled\":true}"))
            lifecycle.initializeAll()
            block(module,ModuleCharacterStateActions(module,lifecycle),db)
        } finally { lifecycles.asReversed().forEach { it.destroyAll() };scope.cancel();delay(100);db.close() }
    }
    private suspend fun seed(db:ZeroDatabase) {
        db.characterDao().insert(CharacterEntity("a","isolated a",systemPrompt="role a",isActive=true))
        db.characterDao().insert(CharacterEntity("b","isolated b",systemPrompt="role b"))
        for((id,role) in listOf("a1" to "a","a2" to "a","b1" to "b","unassigned" to null)) {
            db.conversationDao().insert(ConversationEntity(id,id,characterId=role))
            db.characterStateDao().upsertMoodState(ConversationMoodState(id,"CHEERFUL",0.5f,System.currentTimeMillis()+60_000))
        }
        for(id in listOf("a","b")) db.characterStateDao().upsertRelationState(CharacterRelationState(id,"CLOSE",10,revision=3))
        for(i in 0..6) db.characterStateDao().insertEventIfNew(CharacterStateEvent("event-$i","a","a1",changeType="AFFINITY",reason="reason-$i",idempotencyKey="key-$i",createdAt=i.toLong()))
    }
    private suspend fun waitUntil(condition:()->Boolean) = withTimeout(3000) { while(!condition()) delay(10) }
    private suspend fun withVm(db:ZeroDatabase, actions:ModuleCharacterStateActions, block:suspend (CharacterManageViewModel)->Unit) {
        val owner=ViewModelStore()
        val vm=withContext(Dispatchers.Main) { CharacterManageViewModel(db.characterDao(),actions).also { owner.put("character",it) } }
        try { waitUntil { vm.stateUi.value.loaded };block(vm) } finally { withContext(Dispatchers.Main) { owner.clear() } }
    }
    private suspend fun rejectConfig(db:ZeroDatabase) = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_config_reject BEFORE INSERT ON module_configs WHEN NEW.module_id='character_state' BEGIN SELECT RAISE(ABORT, 'isolated config rejection'); END")
    }
    @Test fun snapshotCancellationMustPropagateThroughActualModuleAndSafeCall() = runBlocking {
        fixture({real->object:CharacterStateDao by real {
            override suspend fun getRelationState(characterId:String):com.zero.v2.core.data.entity.CharacterRelationState? = throw CancellationException("isolated snapshot cancellation")
        }}) {_,actions,_->
            var cancelled=false
            try { actions.snapshot("isolated-role") } catch(_:CancellationException) { cancelled=true }
            assertTrue("Snapshot cancellation must propagate, not become a missing snapshot",cancelled)
        }
    }
    @Test fun rejectedStyleSavePreservesActualModuleAndStoredConfig() = runBlocking {
        fixture {module,actions,db->
            rejectConfig(db)
            var failed=false
            try { actions.setDynamicStyleEnabled(false) } catch(_:IllegalStateException) { failed=true }
            assertTrue(failed)
            assertTrue("Failed save must preserve the active module setting",module.dynamicStyleEnabled.value)
            assertEquals("{\"dynamicStyleEnabled\":true}",db.moduleConfigDao().getByModuleId("character_state")?.configJson)
        }
    }
    @Test fun rejectedManualStyleSaveMustNotDeployLaterThroughRetryQueue() = runBlocking {
        fixture {_,actions,db->
            rejectConfig(db)
            try { actions.setDynamicStyleEnabled(false);fail("Save must fail") } catch(_:IllegalStateException) { }
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER isolated_config_reject") }
            delay(700)
            assertEquals("Rejected manual change must not silently apply later","{\"dynamicStyleEnabled\":true}",db.moduleConfigDao().getByModuleId("character_state")?.configJson)
        }
    }
    @Test fun actualSuccessfulStyleSaveRestoresAndDisablingPreservesAllStates() = runBlocking {
        fixture {module,actions,db->
            seed(db)
            assertNotNull(module.currentStyleDirective("a1"))
            actions.setDynamicStyleEnabled(false)
            assertFalse(module.dynamicStyleEnabled.value);assertNull(module.currentStyleDirective("a1"))
            assertEquals("{\"dynamicStyleEnabled\":false}",db.moduleConfigDao().getByModuleId("character_state")?.configJson)
            assertFalse(restoreFreshModule().dynamicStyleEnabled.value)
            assertEquals(10,db.characterStateDao().getRelationState("a")?.interactionCount)
            assertNotNull(db.characterStateDao().getMoodState("a1"));assertEquals(7,db.characterStateDao().countEventsForCharacter("a"))
            actions.setDynamicStyleEnabled(true);assertTrue(module.dynamicStyleEnabled.value);assertTrue(restoreFreshModule().dynamicStyleEnabled.value)
        }
    }
    @Test fun actualSnapshotAndAffinityResetAreRoleScopedAndKeepMoodAndHistory() = runBlocking {
        fixture {_,actions,db->
            seed(db)
            val before=requireNotNull(actions.snapshot("a"))
            assertEquals(AffinityBand.CLOSE,before.affinity);assertEquals(10,before.interactionCount);assertEquals(3,before.revision)
            assertEquals(listOf("reason-6","reason-5","reason-4","reason-3","reason-2"),before.recentEvents.map { it.reason })
            actions.resetRelationForCharacter("a")
            assertNull(db.characterStateDao().getRelationState("a"));assertNotNull(db.characterStateDao().getRelationState("b"))
            assertNotNull(db.characterStateDao().getMoodState("a1"));assertNotNull(db.characterStateDao().getMoodState("a2"))
            assertEquals(7,db.characterStateDao().countEventsForCharacter("a"))
            assertEquals(AffinityBand.DEFAULT,requireNotNull(actions.snapshot("a")).affinity)
        }
    }
    @Test fun actualMoodResetClearsOnlySelectedRolesConversations() = runBlocking {
        fixture {_,actions,db->
            seed(db);actions.resetMoodForCharacter("a")
            assertNull(db.characterStateDao().getMoodState("a1"));assertNull(db.characterStateDao().getMoodState("a2"))
            assertNotNull(db.characterStateDao().getMoodState("b1"));assertNotNull(db.characterStateDao().getMoodState("unassigned"))
            assertNotNull(db.characterStateDao().getRelationState("a"));assertEquals(7,db.characterStateDao().countEventsForCharacter("a"))
        }
    }
    @Test fun actualSQLiteMoodResetFailureRollsBackAllConversationsAndVmCanRetry() = runBlocking {
        fixture {_,actions,db->
            seed(db)
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_mood_reject BEFORE DELETE ON conversation_mood_states WHEN OLD.conversation_id='a2' BEGIN SELECT RAISE(ABORT, 'isolated mood rejection'); END") }
            withVm(db,actions) {vm->
                withContext(Dispatchers.Main) { vm.resetMood() };waitUntil { !vm.actionState.value.busy }
                assertNotNull(vm.actionState.value.error);assertNull(vm.actionState.value.message)
                for(id in listOf("a1","a2","b1","unassigned")) assertNotNull(db.characterStateDao().getMoodState(id))
                withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER isolated_mood_reject") }
                withContext(Dispatchers.Main) { vm.resetMood() };waitUntil { !vm.actionState.value.busy }
                assertNull(vm.actionState.value.error);assertEquals("已重置情绪",vm.actionState.value.message)
                assertNull(db.characterStateDao().getMoodState("a1"));assertNull(db.characterStateDao().getMoodState("a2"));assertNotNull(db.characterStateDao().getMoodState("b1"))
            }
        }
    }
    @Test fun actualAffinityResetFailureKeepsStateAndReportsVmError() = runBlocking {
        fixture {_,actions,db->
            seed(db)
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_relation_reject BEFORE DELETE ON character_relation_states WHEN OLD.character_id='a' BEGIN SELECT RAISE(ABORT, 'isolated relation rejection'); END") }
            withVm(db,actions) {vm->
                withContext(Dispatchers.Main) { vm.resetAffinity() };waitUntil { !vm.actionState.value.busy }
                assertNotNull(vm.actionState.value.error);assertNull(vm.actionState.value.message)
                assertEquals(10,db.characterStateDao().getRelationState("a")?.interactionCount)
                assertNotNull(db.characterStateDao().getRelationState("b"));assertNotNull(db.characterStateDao().getMoodState("a1"))
            }
        }
    }
    @Test fun actualVmRejectedStyleSavePreservesSwitchThenRetriesSuccessfully() = runBlocking {
        fixture {module,actions,db->
            seed(db);rejectConfig(db)
            withVm(db,actions) {vm->
                withContext(Dispatchers.Main) { vm.setDynamicStyleEnabled(false) };waitUntil { !vm.actionState.value.busy }
                assertNotNull(vm.actionState.value.error);assertNull(vm.actionState.value.message)
                assertTrue(module.dynamicStyleEnabled.value);assertTrue(vm.stateUi.value.dynamicStyleEnabled)
                withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER isolated_config_reject") }
                delay(700);assertTrue(restoreFreshModule().dynamicStyleEnabled.value)
                withContext(Dispatchers.Main) { vm.setDynamicStyleEnabled(false) };waitUntil { !vm.actionState.value.busy && !vm.stateUi.value.dynamicStyleEnabled }
                assertNull(vm.actionState.value.error);assertEquals("已更新动态表达",vm.actionState.value.message)
                assertFalse(restoreFreshModule().dynamicStyleEnabled.value)
            }
        }
    }
    @Test fun actualSnapshotFailureBindsVmErrorAndReloadClearsIt() = runBlocking {
        var reject=true
        fixture({real->object:CharacterStateDao by real {
            override suspend fun getRelationState(characterId:String):CharacterRelationState? {
                if(reject) error("isolated snapshot failure")
                return real.getRelationState(characterId)
            }
        }}) {_,actions,db->
            seed(db)
            withVm(db,actions) {vm->
                assertNotNull(vm.stateUi.value.error);assertTrue(vm.stateUi.value.recentEvents.isEmpty())
                reject=false;withContext(Dispatchers.Main) { vm.reloadCharacters() }
                waitUntil { vm.stateUi.value.loaded && vm.stateUi.value.error==null }
                assertEquals("亲近",vm.stateUi.value.affinityLabel);assertEquals(5,vm.stateUi.value.recentEvents.size)
            }
        }
    }
    @Test fun lateCancellationCompletesLocalCommitAndPublicationTogether() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            fixture(configWrap={real->object:ModuleConfigDao by real {
                override suspend fun upsert(entity:ModuleConfigEntity) {
                    if(entity.configJson.contains("false")) { entered.complete(Unit);release.await() }
                    real.upsert(entity)
                }
            }}) {module,actions,db->
                val change=launch { actions.setDynamicStyleEnabled(false) }
                entered.await();change.cancel();release.complete(Unit);change.join()
                assertTrue(change.isCancelled);assertFalse(module.dynamicStyleEnabled.value)
                assertEquals("{\"dynamicStyleEnabled\":false}",db.moduleConfigDao().getByModuleId("character_state")?.configJson)
                assertFalse(restoreFreshModule().dynamicStyleEnabled.value)
            }
        } finally { release.complete(Unit) }
    }
    @Test fun concurrentActualStyleChangesSerializeAndLastRequestWins() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            fixture(configWrap={real->object:ModuleConfigDao by real {
                override suspend fun upsert(entity:ModuleConfigEntity) {
                    if(entity.configJson.contains("false")) { entered.complete(Unit);release.await() }
                    real.upsert(entity)
                }
            }}) {module,actions,db->
                val first=launch { actions.setDynamicStyleEnabled(false) };entered.await()
                val second=launch { actions.setDynamicStyleEnabled(true) };delay(30)
                val cancelled=launch { actions.setDynamicStyleEnabled(false) };delay(10);cancelled.cancelAndJoin()
                assertTrue(module.dynamicStyleEnabled.value);assertEquals("{\"dynamicStyleEnabled\":true}",db.moduleConfigDao().getByModuleId("character_state")?.configJson)
                release.complete(Unit);first.join();second.join()
                assertTrue(module.dynamicStyleEnabled.value);assertTrue(restoreFreshModule().dynamicStyleEnabled.value)
            }
        } finally { release.complete(Unit) }
    }
    @Test fun styleReadCancellationAlsoPropagatesWithoutFallbackSuccess() = runBlocking {
        fixture({real->object:CharacterStateDao by real {
            override suspend fun getRelationState(characterId:String):CharacterRelationState? = throw CancellationException("isolated style cancellation")
        }}) {module,_,db->
            seed(db);var cancelled=false
            try { module.currentStyleDirective("a1") } catch(_:CancellationException) { cancelled=true }
            assertTrue(cancelled)
        }
    }
    @Test fun actualVmCancelledAfterLocalWriteStartsHasNoFalseSuccessAndNoConfigDivergence() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            fixture(configWrap={real->object:ModuleConfigDao by real {
                override suspend fun upsert(entity:ModuleConfigEntity) {
                    if(entity.configJson.contains("false")) { entered.complete(Unit);release.await() }
                    real.upsert(entity)
                }
            }}) {module,actions,db->
                seed(db);val owner=ViewModelStore()
                val vm=withContext(Dispatchers.Main) { CharacterManageViewModel(db.characterDao(),actions).also { owner.put("character",it) } }
                try {
                    waitUntil { vm.stateUi.value.loaded }
                    withContext(Dispatchers.Main) { vm.setDynamicStyleEnabled(false) };entered.await()
                    withContext(Dispatchers.Main) { owner.clear() };release.complete(Unit)
                    waitUntil { !vm.actionState.value.busy && !module.dynamicStyleEnabled.value }
                    assertNull(vm.actionState.value.message);assertNull(vm.actionState.value.error)
                    assertEquals("{\"dynamicStyleEnabled\":false}",db.moduleConfigDao().getByModuleId("character_state")?.configJson)
                    assertFalse(restoreFreshModule().dynamicStyleEnabled.value)
                } finally { release.complete(Unit);withContext(Dispatchers.Main) { owner.clear() } }
            }
        } finally { release.complete(Unit) }
    }
}
