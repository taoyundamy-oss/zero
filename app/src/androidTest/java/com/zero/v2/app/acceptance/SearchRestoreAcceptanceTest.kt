package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Original search source/config, isolated legacy identities; discovery never performs a search. */
class SearchRestoreAcceptanceTest {
    @Test fun recoverBaselineModulesWhenExplicitlyRequested() = runBlocking {
        val requested = InstrumentationRegistry.getArguments().getString("recoverBaselineModules")
        org.junit.Assume.assumeTrue(!requested.isNullOrBlank())
        val ids = requireNotNull(requested).split(',')
        require(ids.toSet() == setOf("qzone_post", "vision_pro"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        entry.lifecycleManager().safeCall(entry.mcp(), "baseline-recovery-start") { entry.mcp().onStart() }
        for (id in ids) {
            val module = requireNotNull(provider.database().dynamicModuleDao().getById(id))
            assertEquals(true, entry.lifecycleManager().safeCall(entry.mcp(), "baseline-recovery-mount") {
                entry.mcp().mountDynamicModule(module)
            })
            assertEquals("ACTIVE", provider.database().dynamicModuleDao().getById(id)?.state)
        }
    }

    @Test fun recoverOriginalSearchWhenExplicitlyRequested() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("recoverSearch") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val module = requireNotNull(provider.database().dynamicModuleDao().getById("searx_search"))
        entry.lifecycleManager().safeCall(entry.mcp(), "search-recovery-start") { entry.mcp().onStart() }
        assertEquals(true, entry.lifecycleManager().safeCall(entry.mcp(), "search-recovery-mount") {
            entry.mcp().mountDynamicModule(module)
        })
        assertEquals("ACTIVE", provider.database().dynamicModuleDao().getById(module.id)?.state)
        File(context.filesDir, "a8-search-original-recovery.json").writeText(JSONObject()
            .put("module", module.id).put("state", "ACTIVE").put("scope", "real_start_initialize_tool_discovery_not_search_query").toString(2))
    }

    @Test fun originalSearchSourceRestoresOverHttpAndSocket() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val original = provider.database().dynamicModuleDao().getById("searx_search")
        assertNotNull("Original source required for this acceptance", original)
        val rows = org.json.JSONArray()
        for (transport in listOf("http", "socket")) {
            val id = "a8_search_${System.currentTimeMillis()}"
            val module = original!!.copy(id = id, state = "DISABLED", transport = transport, lastEndpoint = "")
            val bridge = entry.termux()
            try {
                assertTrue(bridge.ensureSupervisor())
                val config = provider.releaseManager().configStore.get(original.id)
                if (config != null) provider.releaseManager().configStore.saveConfirmed(id, config)
                assertEquals(true, bridge.supPostRaw("/file/upload?path=/root/zero/modules/$id.py",
                    original.source.toByteArray())?.optBoolean("ok"))
                entry.lifecycleManager().safeCall(entry.mcp(), "search-acceptance-start") { entry.mcp().onStart() }
                val mounted = entry.lifecycleManager().safeCall(entry.mcp(), "search-acceptance-mount") {
                    entry.mcp().mountDynamicModule(module)
                }
                rows.put(JSONObject().put("module", id).put("transport", transport).put("mounted", mounted))
                File(context.filesDir, "a8-search-restore.json").writeText(rows.toString(2))
                assertEquals("Original search source must initialize and expose tools on $transport", true, mounted)
            } finally {
                entry.lifecycleManager().safeCall(entry.mcp(), "search-acceptance-unmount") {
                    entry.mcp().unmountDynamicModule(module)
                }
                provider.database().withTransaction {
                    provider.database().dynamicModuleDao().delete(id)
                    provider.database().moduleConfigDao().delete(id)
                }
                bridge.supPost("/exec", JSONObject().put("cmd", "rm -f /root/zero/modules/$id.py"))
            }
        }
        assertEquals(original, provider.database().dynamicModuleDao().getById(original!!.id))
    }
}
