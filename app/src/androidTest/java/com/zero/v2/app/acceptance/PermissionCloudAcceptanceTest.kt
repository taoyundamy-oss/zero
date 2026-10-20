package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.PermissionCloudCall
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PermissionCloudAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun waitFor(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; Thread.sleep(20) }
        assertTrue("Cloud state did not settle", condition())
    }
    private fun config() = JSONObject().put("forward_whitelist", JSONArray().put("remote"))
        .put("daily_forward_cap", 200).put("auto_forward_enabled", true)

    @Test fun unavailableChannelStaysUnknown() {
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { _, _ -> null }) { vm ->
            inst.waitForIdleSync()
            assertNull("Failed status read must not become disconnected", vm.state.value.wechatLoggedIn)
        }
    }

    @Test fun lateReadKeepsUserDraft() {
        val response = CompletableDeferred<JSONObject?>()
        var started = false
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { tool, _ ->
            if (tool == "im_config_get") { started = true; response.await() } else null
        }) { vm ->
            waitFor { started }
            inst.runOnMainSync { vm.updateImConfig("my draft", 321, false) }
            response.complete(config())
            waitFor { vm.state.value.imConfig.loaded }
            assertEquals("my draft", vm.state.value.imConfig.whitelist)
            assertEquals(321, vm.state.value.imConfig.dailyCap)
            assertFalse(vm.state.value.imConfig.autoForward)
        }
    }

    @Test fun failedReadEndsLoadingAndCanRetry() {
        var available = false
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { tool, _ ->
            if (tool == "im_config_get" && available) config() else null
        }) { vm ->
            waitFor { vm.state.value.imConfig.error != null }
            assertFalse(vm.state.value.imConfig.loading)
            inst.runOnMainSync { available = true; vm.loadImConfig() }
            waitFor { vm.state.value.imConfig.loaded && !vm.state.value.imConfig.loading }
            assertNull(vm.state.value.imConfig.error)
        }
    }

    @Test fun malformedReadDoesNotEnableSaving() {
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { _, _ -> JSONObject().put("error", "rejected") }) { vm ->
            waitFor { vm.state.value.imConfig.error != null }
            assertFalse(vm.state.value.imConfig.loaded)
        }
    }

    @Test fun unconfirmedSaveIsVisibleAndPreservesDraft() {
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { tool, _ ->
            if (tool == "im_config_get") config() else JSONObject().put("ok", true)
        }) { vm ->
            waitFor { vm.state.value.imConfig.loaded }
            inst.runOnMainSync { vm.updateImConfig("draft", 321, false); vm.saveImConfig() }
            waitFor { vm.state.value.imConfig.error != null }
            assertFalse(vm.state.value.imConfig.saving)
            assertTrue(vm.state.value.imConfig.dirty)
            assertEquals("draft", vm.state.value.imConfig.whitelist)
            assertNull(vm.state.value.imConfig.message)
        }
    }

    @Test fun lateSaveReceiptKeepsNewerEditAndOnlyOneRequest() {
        val response = CompletableDeferred<JSONObject?>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { tool, _ ->
            if (tool == "im_config_get") config()
            else if (tool == "im_config_set") { calls.incrementAndGet(); response.await() } else null
        }) { vm ->
            waitFor { vm.state.value.imConfig.loaded }
            inst.runOnMainSync { vm.updateImConfig("submitted", 321, false); vm.saveImConfig(); vm.saveImConfig() }
            waitFor { calls.get() == 1 }
            inst.runOnMainSync { vm.updateImConfig("newer", 654, true) }
            response.complete(JSONObject().put("forward_whitelist", JSONArray().put("submitted"))
                .put("daily_forward_cap", 321).put("auto_forward_enabled", false))
            waitFor { !vm.state.value.imConfig.saving }
            assertEquals(1, calls.get())
            assertEquals("newer", vm.state.value.imConfig.whitelist)
            assertTrue(vm.state.value.imConfig.dirty)
            assertNotNull(vm.state.value.imConfig.message)
            assertNull(vm.state.value.imConfig.error)
        }
    }

    @Test fun invalidCapDoesNotSendOrDiscardText() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { tool, _ ->
            if (tool == "im_config_get") config() else { calls.incrementAndGet(); null }
        }) { vm ->
            waitFor { vm.state.value.imConfig.loaded }
            val baseline = calls.get()
            inst.runOnMainSync { vm.editImConfig("draft", "xyz", true); vm.saveImConfig() }
            waitFor { vm.state.value.imConfig.error != null }
            assertEquals(baseline, calls.get())
            assertEquals("xyz", vm.state.value.imConfig.capText)
        }
    }

    @Test fun cancelLoginCancelsTransportAndRejectsDuplicateStart() {
        val response = CompletableDeferred<JSONObject?>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { tool, _ ->
            if (tool == "im_login") {
                calls.incrementAndGet()
                try { response.await() } finally { cancelled.set(true) }
            } else null
        }) { vm ->
            inst.runOnMainSync { vm.startImLogin(); vm.startImLogin() }
            waitFor { calls.get() == 1 }
            assertTrue(vm.state.value.imLoginPolling)
            inst.runOnMainSync { vm.cancelImLogin() }
            waitFor { cancelled.get() && !vm.state.value.imLoginPolling }
            assertEquals(1, calls.get())
            assertTrue(vm.state.value.imLoginUrl.isEmpty())
        }
    }

    @Test fun cancelledSaveReleasesBusyAndKeepsUncertainReceipt() {
        val response = CompletableDeferred<JSONObject?>()
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { tool, _ ->
            if (tool == "im_config_get") config()
            else if (tool == "im_config_set") {
                started.set(true)
                try { response.await() } finally { cancelled.set(true) }
            } else null
        }) { vm ->
            waitFor { vm.state.value.imConfig.loaded }
            inst.runOnMainSync { vm.updateImConfig("draft", 321, false); vm.saveImConfig() }
            waitFor { started.get() }
            inst.runOnMainSync { vm.cancelImConfig() }
            waitFor { cancelled.get() && !vm.state.value.imConfig.saving }
            assertTrue(vm.state.value.imConfig.dirty)
            assertEquals("draft", vm.state.value.imConfig.whitelist)
            assertTrue(vm.state.value.imConfig.message!!.contains("远端可能已保存"))
        }
    }

    @Test fun confirmedReceiptClearsDirty() {
        val cloud = PermissionCloudCall { tool, _ -> if (tool == "im_config_get" || tool == "im_config_set") config() else null }
        PermissionStatusAcceptanceTest().fixture(cloud = cloud) { vm ->
            waitFor { vm.state.value.imConfig.loaded }
            inst.runOnMainSync { vm.updateImConfig("remote", 200, true); vm.saveImConfig() }
            waitFor { vm.state.value.imConfig.message == "参数已保存。" }
            assertFalse(vm.state.value.imConfig.dirty)
            assertNull(vm.state.value.imConfig.error)
        }
    }

    @Test fun reconstructedViewModelKeepsSavedDraft() {
        val saved = androidx.lifecycle.SavedStateHandle()
        val cloud = PermissionCloudCall { tool, _ -> if (tool == "im_config_get") config() else null }
        PermissionStatusAcceptanceTest().fixture(cloud = cloud, saved = saved) { vm ->
            waitFor { vm.state.value.imConfig.loaded }
            inst.runOnMainSync { vm.editImConfig("restored draft", "321", false) }
        }
        var restored: androidx.lifecycle.SavedStateHandle? = null
        inst.runOnMainSync {
            restored = androidx.lifecycle.SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        }
        PermissionStatusAcceptanceTest().fixture(cloud = cloud, saved = restored!!) { vm ->
            waitFor { vm.state.value.imConfig.loaded }
            assertEquals("restored draft", vm.state.value.imConfig.whitelist)
            assertEquals("321", vm.state.value.imConfig.capText)
            assertFalse(vm.state.value.imConfig.autoForward)
            assertTrue(vm.state.value.imConfig.dirty)
        }
    }
}
