package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ModuleReleaseManifest
import com.zero.v2.core.data.entity.DynamicModuleEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real supervisor busy response and Android wait; only isolated owned releases are changed. */
class DynamicStartBusyAcceptanceTest {
    @Test fun busyStartWaitsAndRegistersToolsWithoutDisablingModule() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val id = "a8_busy_${System.currentTimeMillis()}"
        val marker = "/root/zero/$id.marker"
        val code = """
            import zero_tool, os, time, pathlib
            original = zero_tool._dispatch
            first = True
            def dispatch(request):
                global first
                if request.get('id') == 'health' and first and os.environ.get('ZERO_RELEASE_PROBE') != '1':
                    first = False
                    pathlib.Path('$marker').write_text('entered')
                    time.sleep(6)
                return original(request)
            zero_tool._dispatch = dispatch
            @zero_tool.tool
            def echo(value:int):
                return {'value':value}
            zero_tool.serve()
        """.trimIndent() + "\n"
        val module = DynamicModuleEntity(id, source = code, createdAt = System.currentTimeMillis())
        try {
            assertTrue(provider.releaseManager().publish(id, code, ModuleReleaseManifest(sdkRange = ">=1.1,<2")).ok)
            entry.termux().supPost("/exec", JSONObject().put("cmd", "rm -f $marker"))
            val firstStart = async(Dispatchers.IO) {
                entry.termux().supPost("/module/$id/start", JSONObject().put("transport", "http"))
            }
            withTimeout(15_000) {
                while (entry.termux().supGet("/read?path=$marker")?.optString("text").isNullOrBlank()) delay(100)
            }
            val rejected = entry.termux().supPost("/module/$id/start", JSONObject().put("transport", "http"))
            assertEquals("control_plane_busy", rejected?.optString("error"))
            entry.lifecycleManager().safeCall(entry.mcp(), "busy-acceptance-start") { entry.mcp().onStart() }
            assertEquals(true, withTimeout(60_000) {
                entry.lifecycleManager().safeCall(entry.mcp(), "busy-acceptance-mount") { entry.mcp().mountDynamicModule(module) }
            })
            assertTrue(firstStart.await()?.optBoolean("ok") == true)
            assertEquals("ACTIVE", provider.database().dynamicModuleDao().getById(id)?.state)
            val result = entry.dispatcher().execute("dyn_${id}_echo", mapOf("value" to 7))
            assertTrue(result.errorMessage, result.success)
            assertEquals(7, JSONObject(result.data).getInt("value"))
        } finally {
            entry.lifecycleManager().safeCall(entry.mcp(), "busy-acceptance-unmount") { entry.mcp().unmountDynamicModule(module) }
            provider.database().dynamicModuleDao().delete(id)
            entry.termux().supPost("/exec", JSONObject().put("cmd", "rm -f $marker"))
        }
    }
}
