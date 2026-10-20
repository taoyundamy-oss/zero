package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.McpConnectionActions
import com.zero.v2.app.ui.settings.McpServersViewModel
import com.zero.v2.services.mcp.McpServerStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Actual production save owner, isolated preferences/credentials and discovery boundary; no personal server requests. */
class McpSaveStateAcceptanceTest {
    @Test fun validationAndRejectedSaveKeepDraftCallbackPendingThenManualRetryConfirms() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val backend = RejectingPreferences("[]")
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = backend.preferences
        }
        val traces = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java).traces()
        val store = McpServerStore(owned, traces)
        val credentials = OwnedCredentials()
        val actions = object : McpConnectionActions {
            override val discoveredCount = MutableStateFlow(0)
            var calls = 0
            override suspend fun refresh(): com.zero.v2.services.mcp.McpRefreshReport { calls++; error("owned-discovery-failure") }
        }
        val owner = ViewModelStore()
        lateinit var vm: McpServersViewModel
        var confirmations = 0
        instrumentation.runOnMainSync {
            vm = McpServersViewModel(store, credentials, actions)
            owner.put("owned", vm)
        }
        try {
            instrumentation.runOnMainSync {
                vm.addServer("Owned", "not-an-http-url", "owned-key", onSaved = { confirmations++ })
            }
            assertTrue(store.servers.value.isEmpty())
            assertEquals(0, confirmations)
            assertNotNull(vm.state.value.error)
            backend.reject = true
            instrumentation.runOnMainSync {
                vm.addServer("Owned", "https://owned.example/mcp", "owned-key", onSaved = { confirmations++ })
            }
            await { !vm.state.value.saving && vm.state.value.error?.contains("保存失败") == true }
            assertTrue(store.servers.value.isEmpty())
            assertEquals(0, confirmations)
            assertEquals(0, actions.calls)
            backend.reject = false
            instrumentation.runOnMainSync {
                vm.addServer("Owned", "https://owned.example/mcp", "owned-key", onSaved = { confirmations++ })
            }
            await { !vm.state.value.saving && !vm.state.value.refreshing && actions.calls == 1 }
            assertEquals(1, confirmations)
            assertEquals(1, store.servers.value.size)
            assertEquals("owned-key", credentials.getString(store.servers.value.single().apiKeyRef))
            assertTrue(vm.state.value.error!!.contains("工具发现失败"))
            assertEquals(store.servers.value, McpServerStore(owned, traces).servers.value)
        } finally { instrumentation.runOnMainSync { owner.clear() } }
    }

    private fun await(condition: () -> Boolean) {
        repeat(200) { if (condition()) return; Thread.sleep(25) }
        assertTrue("Owned save state did not settle", condition())
    }
}
