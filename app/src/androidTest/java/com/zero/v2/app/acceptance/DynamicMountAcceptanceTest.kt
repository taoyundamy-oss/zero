package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ModuleReleaseManifest
import com.zero.v2.core.data.entity.DynamicModuleEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Drive real release/start/initialize overlap without changing personal modules or calling models. */
class DynamicMountAcceptanceTest {
    @Test fun overlappingMountsKeepBothHandshakesAndLatestToolUsable() = runBlocking {
        verifyOverlap("http")
    }

    @Test fun overlappingSocketMountsKeepBothHandshakesAndLatestToolUsable() = runBlocking {
        verifyOverlap("socket")
    }

    private suspend fun verifyOverlap(transport: String) = kotlinx.coroutines.coroutineScope {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val id = "a8_mount_${System.currentTimeMillis()}"
        val marker = "/root/zero/$id.marker"
        val source = """
            import zero_tool
            import os
            import pathlib
            import time
            dispatch = zero_tool._dispatch
            def delayed_initialize(request):
                if request.get('method') == 'initialize' and isinstance(request.get('id'), int):
                    pathlib.Path('$marker').write_text(str(os.getpid()))
                    time.sleep(4)
                return dispatch(request)
            zero_tool._dispatch = delayed_initialize
            @zero_tool.tool
            def echo(value:int):
                return {'value':value}
            if os.environ.get('ZERO_MOD_SOCKET'):
                zero_tool.serve_socket(os.environ['ZERO_MOD_SOCKET'])
            else:
                zero_tool.serve()
        """.trimIndent() + "\n"
        val module = DynamicModuleEntity(id = id, source = source, transport = transport, createdAt = System.currentTimeMillis())
        val manager = entry.lifecycleManager()
        val mcp = entry.mcp()
        try {
            assertTrue(provider.releaseManager().publish(id, source, ModuleReleaseManifest(sdkRange = ">=1.1,<2")).ok)
            manager.safeCall(mcp, "acceptance-start") { mcp.onStart() }
            val first = async(Dispatchers.IO) {
                manager.safeCall(mcp, "overlap-first") { mcp.mountDynamicModule(module) }
            }
            withTimeout(20_000) {
                while (entry.termux().supGet("/read?path=$marker")?.optString("text").isNullOrBlank()) {
                    delay(100)
                }
            }
            val second = async(Dispatchers.IO) {
                manager.safeCall(mcp, "overlap-second") { mcp.mountDynamicModule(module) }
            }
            val results = withTimeout(90_000) { listOf(first.await(), second.await()) }
            File(context.filesDir, "a8-mount-$transport.json").writeText(JSONObject()
                .put("module", id).put("transport", transport).put("first", results[0]).put("second", results[1])
                .put("initializationMarkerObserved", true).toString(2))
            assertEquals("Both actual initialize calls must complete", listOf(true, true), results)
            val result = entry.dispatcher().execute("dyn_${id}_echo", mapOf("value" to 7))
            assertTrue(result.errorMessage, result.success)
            assertEquals(7, JSONObject(result.data).getInt("value"))
            assertEquals("ACTIVE", provider.database().dynamicModuleDao().getById(id)?.state)
        } finally {
            manager.safeCall(mcp, "acceptance-unmount") { mcp.unmountDynamicModule(module) }
            provider.database().dynamicModuleDao().delete(id)
            entry.termux().supPost("/module/$id/stop", JSONObject())
        }
    }
}
