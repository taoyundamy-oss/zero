package com.zero.v2.app.acceptance

import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.JumpTarget
import com.zero.v2.app.ui.settings.PermStatus
import com.zero.v2.app.ui.settings.PermissionCenterViewModel
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.shizuku.ShizukuBridge
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real ViewModel and system reads; isolated trace/DAO, disconnected MCP, no permission mutations. */
class PermissionStatusAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext

    internal fun fixture(probeContext: Context = context, cloud: com.zero.v2.app.ui.settings.PermissionCloudCall? = null,
        saved: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(), block: (PermissionCenterViewModel) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val traces = AgentTraceManager(db.agentTraceEventDao())
        val store = ViewModelStore()
        try {
            var failure: Throwable? = null
            var model: PermissionCenterViewModel? = null
            inst.runOnMainSync {
                try {
                    val vm = PermissionCenterViewModel(probeContext, entry.lifecycleManager(), traces,
                        ShizukuBridge(probeContext, SystemBusImpl(), entry.toolRegistrar(), traces),
                        entry.termux(), db.dynamicModuleDao(), cloud ?: com.zero.v2.app.ui.settings.PermissionCloudCall { tool, args -> entry.mcp().callToolForServer("zero-cloud", tool, args) }, saved)
                    store.put("permission-status", vm)
                    model = vm
                } catch (e: Throwable) { failure = e }
            }
            failure?.let { throw it }
            block(requireNotNull(model))
        } finally {
            inst.runOnMainSync { store.clear() }
            Thread.sleep(200)
            db.close()
        }
    }

    private fun failedAlarmProbe(failure: RuntimeException) = object : ContextWrapper(context) {
        override fun getSystemService(name: String): Any? {
            if (name == Context.ALARM_SERVICE) throw failure
            return super.getSystemService(name)
        }
    }

    @Test fun failedExactAlarmProbeDoesNotClaimGranted() {
        fixture(failedAlarmProbe(SecurityException("isolated permission probe denied"))) { vm ->
            assertEquals(PermStatus.DENIED, vm.state.value.items.single { it.key == "exact_alarm" }.status)
            vm.refresh()
            assertEquals(PermStatus.DENIED, vm.state.value.items.single { it.key == "exact_alarm" }.status)
        }
    }

    @Test fun cancelledProbeDoesNotClaimAuthorization() {
        val cancelled = CancellationException("isolated probe cancelled")
        try {
            fixture(failedAlarmProbe(cancelled)) { fail("Cancelled read must propagate") }
            fail("Cancelled read must propagate")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
    }

    @Test fun refreshedExactStatusMatchesRealPlatform() {
        fixture { vm ->
            repeat(2) {
                vm.refresh()
                val granted = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                assertEquals(if (granted) PermStatus.GRANTED else PermStatus.DENIED,
                    vm.state.value.items.single { it.key == "exact_alarm" }.status)
            }
        }
    }

    @Test fun installedTermuxDoesNotPretendBootExtensionInstalled() {
        fun installed(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        assumeTrue(installed("com.termux") && !installed("com.termux.boot"))
        fixture { vm ->
            assertTrue(vm.state.value.termux.installed)
            assertFalse("Termux and Termux:Boot are different packages", vm.state.value.termux.bootInstalled)
            val intent = vm.jumpIntent(JumpTarget.TERMUX_BOOT)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://f-droid.org/packages/com.termux.boot/", intent.data.toString())
        }
    }
}
