package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.*
import com.zero.v2.core.data.dao.CharacterDao
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.CharacterEntity
import com.zero.v2.core.data.store.CharacterRepository
import com.zero.v2.framework.chat.CharacterStateSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Actual ViewModel/SQLite, isolated characters. Capture only expected injected red-version exceptions. */
class CharacterActionsAcceptanceTest {
    private val original = CharacterEntity("isolated-old", "original", systemPrompt = "original", isActive = true)
    private val target = CharacterEntity("isolated-new", "target", systemPrompt = "target")
    private open class Actions : CharacterStateActions {
        override val dynamicStyleEnabled = MutableStateFlow(true)
        override suspend fun snapshot(characterId: String): CharacterStateSnapshot? = null
        override suspend fun setDynamicStyleEnabled(enabled: Boolean) { dynamicStyleEnabled.value = enabled }
        override suspend fun resetRelationForCharacter(characterId: String) = Unit
        override suspend fun resetMoodForCharacter(characterId: String) = Unit
    }
    private suspend fun fixture(wrap: (CharacterDao) -> CharacterDao = { it }, actions: CharacterStateActions = Actions(), block: suspend (CharacterManageViewModel, ZeroDatabase, AtomicInteger) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ZeroDatabase::class.java).build()
        val owner = ViewModelStore(); val failures = AtomicInteger()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (generateSequence(error) { it.cause }.any { it.message?.contains("isolated injected") == true }) failures.incrementAndGet()
            else previous?.uncaughtException(thread, error)
        }
        try {
            db.characterDao().insert(original); db.characterDao().insert(target)
            val vm = withContext(Dispatchers.Main) { CharacterManageViewModel(wrap(db.characterDao()), actions).also { owner.put("character", it) } }
            block(vm, db, failures)
        } finally { withContext(Dispatchers.Main) { owner.clear() }; db.close(); Thread.setDefaultUncaughtExceptionHandler(previous) }
    }
    private fun actionError(vm: CharacterManageViewModel): String? = vm.javaClass.methods.firstOrNull { it.name == "getActionState" }
        ?.invoke(vm)?.let { flow -> (flow as kotlinx.coroutines.flow.StateFlow<*>).value?.let { state -> state.javaClass.methods.firstOrNull { it.name == "getError" }?.invoke(state) } } as? String
    @Test fun missingActivationTargetPreservesCurrentCharacter() = runBlocking {
        fixture { vm, db, _ ->
            withContext(Dispatchers.Main) { vm.setActive("isolated-missing") }; delay(250)
            assertEquals("Unknown target must preserve current role", original.id, db.characterDao().getActive()?.id)
            assertNotNull(actionError(vm))
        }
    }
    @Test fun actualSQLiteActivationFailureRollsBackPreviousRole() = runBlocking {
        fixture { vm, db, failures ->
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_activation_failure BEFORE UPDATE OF is_active ON characters WHEN NEW.id='isolated-new' AND NEW.is_active=1 BEGIN SELECT RAISE(ABORT, 'isolated injected activation failure'); END") }
            withContext(Dispatchers.Main) { vm.setActive(target.id) }; delay(250)
            assertEquals("Partial activation must roll back", original.id, db.characterDao().getActive()?.id)
            assertNotNull(actionError(vm)); assertEquals("Owner must handle failure", 0, failures.get())
        }
    }
    @Test fun sameFrameCreationReservesOneWrite() = runBlocking {
        val entered = AtomicInteger(); val release = CompletableDeferred<Unit>()
        try {
            fixture({ real -> object : CharacterDao by real {
                override suspend fun insert(character: CharacterEntity) { entered.incrementAndGet(); release.await(); real.insert(character) }
            } }) { vm, db, _ ->
                withContext(Dispatchers.Main) { vm.startCreate(); repeat(5) { vm.saveCharacter("created", null, "prompt") } }
                delay(100); assertEquals("One user submit must produce one insert", 1, entered.get())
                release.complete(Unit); delay(200); assertEquals(3, db.characterDao().count())
            }
        } finally { release.complete(Unit) }
    }
    @Test fun failedEditRetainsEditorAndStoredValuesWithVisibleError() = runBlocking {
        fixture { vm, db, failures ->
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_edit_failure BEFORE UPDATE OF name ON characters WHEN NEW.name='changed' BEGIN SELECT RAISE(ABORT, 'isolated injected update failure'); END") }
            withContext(Dispatchers.Main) { vm.startEdit(target); vm.saveCharacter("changed", null, "changed") }; delay(250)
            assertTrue(vm.editState.value is CharacterEditState.Editing)
            assertEquals("target", db.characterDao().getById(target.id)?.name)
            assertNotNull("Read/write failure must be visible", actionError(vm)); assertEquals(0, failures.get())
        }
    }
    @Test fun cancelledSaveKeepsEditorAndAllowsRetryWithoutFalseSuccess() = runBlocking {
        var attempts = 0
        fixture({ real -> object : CharacterDao by real {
            override suspend fun insert(character: CharacterEntity) {
                if (++attempts == 1) throw CancellationException("isolated cancelled creation")
                real.insert(character)
            }
        } }) { vm, db, failures ->
            withContext(Dispatchers.Main) { vm.startCreate(); vm.saveCharacter("created", null, "prompt") }; delay(150)
            assertEquals(2, db.characterDao().count()); assertEquals(CharacterEditState.Creating, vm.editState.value)
            assertFalse(vm.actionState.value.busy); assertNull(vm.actionState.value.error); assertNull(vm.actionState.value.message)
            withContext(Dispatchers.Main) { vm.saveCharacter("created", null, "prompt") }; delay(200)
            assertEquals(3, db.characterDao().count()); assertEquals(CharacterEditState.Hidden, vm.editState.value); assertEquals(0, failures.get())
        }
    }
    @Test fun forgedPresetDeletionIsRejectedByStoredIdentity() = runBlocking {
        fixture { vm, db, _ ->
            val preset = CharacterEntity("isolated-preset", "preset", systemPrompt = "prompt", isPreset = true)
            db.characterDao().insert(preset)
            withContext(Dispatchers.Main) { vm.deleteCharacter(preset.copy(isPreset = false)) }; delay(200)
            assertNotNull(db.characterDao().getById(preset.id)); assertNotNull(vm.actionState.value.error)
            assertEquals(original.id, db.characterDao().getActive()?.id)
        }
    }
    @Test fun editorForDeletedRecordCannotReportSuccessfulSave() = runBlocking {
        fixture { vm, db, _ ->
            withContext(Dispatchers.Main) { vm.startEdit(target) }; db.characterDao().delete(target.id)
            withContext(Dispatchers.Main) { vm.saveCharacter("changed", null, "prompt") }; delay(200)
            assertNull(db.characterDao().getById(target.id)); assertTrue(vm.editState.value is CharacterEditState.Editing)
            assertNotNull(vm.actionState.value.error); assertNull(vm.actionState.value.message)
        }
    }
    @Test fun failedListReadRetainsLastListAndCanReload() = runBlocking {
        val failRead = CompletableDeferred<Unit>(); var subscriptions = 0
        try {
            fixture({ real -> object : CharacterDao by real {
                override fun observeAll() = kotlinx.coroutines.flow.flow {
                    if (++subscriptions == 1) { emit(real.getAll()); failRead.await(); error("isolated injected read failure") }
                    else emitAll(real.observeAll())
                }
            } }) { vm, _, failures ->
                withTimeout(2000) { while (vm.characters.value.size != 2) delay(10) }
                failRead.complete(Unit); withTimeout(2000) { while (vm.actionState.value.readError == null) delay(10) }
                assertEquals(2, vm.characters.value.size)
                withContext(Dispatchers.Main) { vm.reloadCharacters() }
                withTimeout(2000) { while (vm.actionState.value.loading || vm.actionState.value.readError != null) delay(10) }
                assertEquals(2, vm.characters.value.size); assertEquals(0, failures.get())
            }
        } finally { failRead.complete(Unit) }
    }
    @Test fun cancelledModuleActionDoesNotReportSuccessOrCrash() = runBlocking {
        fixture(actions = object : Actions() {
            override suspend fun setDynamicStyleEnabled(enabled: Boolean) { throw CancellationException("isolated module cancellation") }
        }) { vm, db, failures ->
            withContext(Dispatchers.Main) { vm.setDynamicStyleEnabled(false) }; delay(100)
            assertTrue(vm.stateUi.value.dynamicStyleEnabled); assertFalse(vm.actionState.value.busy)
            assertNull(vm.actionState.value.error); assertNull(vm.actionState.value.message)
            assertEquals(2, db.characterDao().count()); assertEquals(0, failures.get())
        }
    }
    @Test fun sharedWriterMakesChatFallbackObserveCommittedSelection() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            fixture { _, db, _ ->
                val repo = CharacterRepository(object : CharacterDao by db.characterDao() {
                    override suspend fun activateExisting(id: String): CharacterEntity { entered.complete(Unit); release.await(); return db.characterDao().activateExisting(id) }
                })
                val selecting = async { repo.activate(target.id) }; withTimeout(2000) { entered.await() }
                val fallback = async { repo.ensureActive() }; delay(100); assertFalse("Fallback must wait for owned write", fallback.isCompleted)
                release.complete(Unit); assertEquals(target.id, selecting.await().id); assertEquals(target.id, fallback.await()?.id)
                assertEquals(1, db.characterDao().getAll().count { it.isActive }); assertEquals("original", db.characterDao().getById(original.id)?.systemPrompt)
            }
        } finally { release.complete(Unit) }
    }
}
