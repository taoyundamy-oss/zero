package com.zero.v2.app.acceptance

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.BackupProgress
import com.zero.v2.app.data.ExportStats
import com.zero.v2.app.data.ImportStats
import com.zero.v2.app.ui.settings.BackupActions
import com.zero.v2.app.ui.settings.DataExportViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BackupOperationAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val uri = Uri.parse("file:///owned-only.json")
    private val stats = ExportStats(1, 2, 3, 4, 5)
    private suspend fun waitFor(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(10) }

    @Test fun duplicateStartsBlockedCancelClearsBusyAndFailureCanRetry() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val actions = object : BackupActions {
            override suspend fun export(uri: Uri, progress: (BackupProgress) -> Unit): Result<ExportStats> {
                calls++
                if (calls == 1) { started.complete(Unit); awaitCancellation() }
                if (calls == 2) error("owned failure")
                return Result.success(stats)
            }
            override suspend fun restore(uri: Uri, progress: (BackupProgress) -> Unit): Result<ImportStats> {
                error("Duplicate restore must not start")
            }
        }
        val owner = ViewModelStore()
        lateinit var vm: DataExportViewModel
        inst.runOnMainSync { vm = DataExportViewModel(actions); owner.put("owned", vm); vm.exportTo(uri) }
        try {
            started.await()
            inst.runOnMainSync { vm.exportTo(uri); vm.importFrom(uri); vm.cancel() }
            waitFor { !vm.state.value.busy }
            assertEquals(1, calls)
            assertNull(vm.state.value.lastExport)
            assertNotNull(vm.state.value.notice)
            inst.runOnMainSync { vm.exportTo(uri) }
            waitFor { !vm.state.value.busy }
            assertTrue(vm.state.value.error.orEmpty().contains("owned failure"))
            inst.runOnMainSync { vm.exportTo(uri) }
            waitFor { !vm.state.value.busy }
            assertEquals(stats, vm.state.value.lastExport)
            assertNull(vm.state.value.error)
        } finally { inst.runOnMainSync { owner.clear() } }
    }

    @Test fun lateSuccessAfterCancellationIsNotPublishedAndOldProgressCannotChangeNextRun() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var callback: ((BackupProgress) -> Unit)? = null
        var calls = 0
        val actions = object : BackupActions {
            override suspend fun export(uri: Uri, progress: (BackupProgress) -> Unit): Result<ExportStats> {
                calls++
                if (calls == 1) {
                    callback = progress
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                } else awaitCancellation()
                return Result.success(stats)
            }
            override suspend fun restore(uri: Uri, progress: (BackupProgress) -> Unit) = Result.success(ImportStats(0,0,0,0,0,0))
        }
        val owner = ViewModelStore()
        lateinit var vm: DataExportViewModel
        inst.runOnMainSync { vm = DataExportViewModel(actions); owner.put("owned", vm); vm.exportTo(uri) }
        try {
            entered.await()
            inst.runOnMainSync { vm.cancel() }
            release.complete(Unit)
            waitFor { !vm.state.value.busy }
            assertNull(vm.state.value.lastExport)
            inst.runOnMainSync { vm.exportTo(uri) }
            waitFor { calls == 2 }
            callback!!.invoke(BackupProgress("obsolete late stage", false))
            assertEquals("正在准备", vm.state.value.progress?.label)
            inst.runOnMainSync { vm.cancel() }
            waitFor { !vm.state.value.busy }
        } finally { release.complete(Unit); inst.runOnMainSync { owner.clear() } }
    }

    @Test fun writePhaseCannotBeCancelledFromUi() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actions = object : BackupActions {
            override suspend fun export(uri: Uri, progress: (BackupProgress) -> Unit): Result<ExportStats> {
                progress(BackupProgress("writing owned file", false))
                started.complete(Unit); release.await()
                return Result.success(stats)
            }
            override suspend fun restore(uri: Uri, progress: (BackupProgress) -> Unit) = Result.success(ImportStats(0,0,0,0,0,0))
        }
        val owner = ViewModelStore()
        lateinit var vm: DataExportViewModel
        inst.runOnMainSync { vm = DataExportViewModel(actions); owner.put("owned", vm); vm.exportTo(uri) }
        try {
            started.await()
            inst.runOnMainSync { vm.cancel() }
            assertTrue(vm.state.value.busy)
            release.complete(Unit)
            waitFor { !vm.state.value.busy }
            assertEquals(stats, vm.state.value.lastExport)
        } finally { release.complete(Unit); inst.runOnMainSync { owner.clear() } }
    }
}
