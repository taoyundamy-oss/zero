package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DeploymentStatusViewModel
import com.zero.v2.core.common.ModuleReleaseManifest
import com.zero.v2.core.common.ReleaseState
import com.zero.v2.core.moduleapi.StatusFact
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Only publishes a dedicated, side-effect-free fixture; never injects failures into daily modules. */
class ReleaseLifecycleAcceptanceTest {
    @Test fun hostEmergencyAndRestartKeepsDisabledFixtureStopped() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeTrue("Host interruption requires an explicit test flag",
            InstrumentationRegistry.getArguments().getString("allowHostInterruption") == "true")
        val power = context.getSystemService(android.os.PowerManager::class.java)
        org.junit.Assume.assumeFalse("Wait until the user is absent", power.isInteractive)
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val bridge = entry.termuxBridge()
        val cfg = bridge.config as com.zero.v2.services.termux.TermuxBridgeConfig
        val before = withTimeout(90_000) {
            var health = bridge.health()
            while (health == null || health.raw.getJSONArray("modules").let { rows ->
                    (0 until rows.length()).count { rows.getJSONObject(it).getString("state") == "ACTIVE" } } < 7) {
                delay(500)
                health = bridge.health()
            }
            health
        }
        val modules = before.raw.getJSONArray("modules")
        val daily = (0 until modules.length()).map { modules.getJSONObject(it) }
            .filter { it.getString("state") == "ACTIVE" && !it.getString("id").startsWith("a6_acceptance_") }
            .map { it.getString("id") }.toSet()
        assertEquals(7, daily.size)
        val status = bridge.supGet("/release/status?module_id=image_gen")!!
        org.junit.Assume.assumeFalse("Preserve an existing user emergency stop", status.getBoolean("emergency"))
        val previous = cfg.emergencyStop

        suspend fun restart() {
            val pid = bridge.health()!!.pid
            val prefix = "/data/data/com.termux/files/usr"
            val command = "grep -aq 'zero_supervisor.py' /proc/$pid/cmdline && kill -TERM $pid"
            assertEquals(0, bridge.awaitCommand("$prefix/bin/bash", listOf("-c", command), timeoutMs = 15_000)?.second)
            withTimeout(20_000) { while (bridge.health() != null) delay(500) }
            assertTrue(bridge.sendCommand("$prefix/bin/python3",
                listOf("$prefix/zero/zero_sdk/zero_supervisor.py", bridge.supervisorPort.toString()), background = true))
            delay(500) // Let the native owner start before asking the bridge to ensure it.
            assertTrue(bridge.ensureSupervisor())
            assertNotEquals(pid, bridge.health()!!.pid)
        }

        try {
            cfg.emergencyStop = true
            assertTrue(bridge.supPost("/release/emergency", JSONObject().put("enabled", true))!!.getBoolean("ok"))
            val stopped = bridge.health()!!.raw.getJSONArray("modules")
            assertTrue((0 until stopped.length()).all { stopped.getJSONObject(it).getString("state") == "STOPPED" })
            val probe = OkHttpClient.Builder().callTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
            for (index in 0 until modules.length()) {
                val row = modules.getJSONObject(index)
                if (row.getString("id") !in daily) continue
                val endpoint = row.getString("endpoint")
                assertTrue("Read-only stop probe expects the current HTTP daily modules", endpoint.startsWith("http://127.0.0.1:"))
                val request = Request.Builder().url(endpoint).post(
                    """{"jsonrpc":"2.0","id":"stop","method":"initialize"}"""
                        .toRequestBody("application/json".toMediaType())).build()
                assertTrue("Stopped endpoint must be unreachable", runCatching { probe.newCall(request).execute().close() }.isFailure)
            }
            restart()
            assertTrue(bridge.supGet("/release/status?module_id=image_gen")!!.getBoolean("emergency"))
            assertEquals(0, bridge.health()!!.raw.getJSONArray("modules").length())
            cfg.emergencyStop = false
            assertTrue(bridge.supPost("/release/emergency", JSONObject().put("enabled", false))!!.getBoolean("ok"))
            restart()
            val restored = bridge.health()!!.raw.getJSONArray("modules")
            assertEquals(daily, (0 until restored.length()).map { restored.getJSONObject(it).getString("id") }.toSet())
            assertTrue((0 until restored.length()).all { restored.getJSONObject(it).getString("state") == "ACTIVE" })
            File(context.filesDir, "a6-host-recovery-report.json").writeText(JSONObject()
                .put("completed", true).put("emergencyPersistedAcrossRestart", true)
                .put("disabledFixturesNotRevived", true).put("dailyRestored", org.json.JSONArray(daily))
                .put("oldEndpointsUnreachable", true)
                .put("originalPid", before.pid).put("restoredPid", bridge.health()!!.pid)
                .put("providerRequests", 0).toString(2))
        } finally {
            cfg.emergencyStop = previous
            bridge.supPost("/release/emergency", JSONObject().put("enabled", previous))
            if (!previous) {
                val observed = bridge.health()?.raw?.getJSONArray("modules")
                val active = observed?.let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }
                    .filter { it.getString("state") == "ACTIVE" }.map { it.getString("id") }.toSet() }.orEmpty()
                if (!active.containsAll(daily)) {
                    if (bridge.health() == null) assertTrue(bridge.ensureSupervisor()) else restart()
                }
            }
        }
    }

    @Test fun realPublicationRollbackAndVersionCardUseSameAuthority() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val release = entry.releaseManager()
        val bridge = entry.termuxBridge()
        val id = "a6_acceptance_${System.currentTimeMillis()}"
        val manifest = ModuleReleaseManifest(sdkRange = ">=1.1,<2", inputSchema = """{"type":"object"}""",
            outputSchema = """{"type":"integer"}""")
        val source = "import zero_tool\n@zero_tool.tool\ndef echo(value:int):\n    return value\nzero_tool.serve()\n"
        val viewModel = DeploymentStatusViewModel(entry.systemState())
        val owner = ViewModelStore()
        owner.put("a6", viewModel)
        val client = OkHttpClient()

        suspend fun call(value: Any): JSONObject {
            val modules = bridge.health()!!.raw.getJSONArray("modules")
            val row = (0 until modules.length()).map { modules.getJSONObject(it) }.single { it.getString("id") == id }
            val body = JSONObject().put("jsonrpc", "2.0").put("id", "a6").put("method", "tools/call")
                .put("params", JSONObject().put("name", "echo").put("arguments", JSONObject().put("value", value)))
            val response = client.newCall(Request.Builder().url(row.getString("endpoint"))
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
            return response.use { JSONObject(it.body!!.string()).getJSONObject("result") }
        }

        suspend fun assertCard(deploymentId: String) {
            val authority = release.readStatus(id)
            assertEquals(deploymentId, authority.active!!.deploymentId)
            instrumentation.runOnMainSync { viewModel.load(id) }
            withTimeout(90_000) {
                while (viewModel.state.value.active != authority.active) delay(500)
            }
            assertEquals(authority.active, viewModel.state.value.active)
            assertNull(authority.errorCode)
            assertEquals(StatusFact.LIVE, viewModel.state.value.freshness)
            assertEquals(authority.recent, viewModel.state.value.history)
        }

        try {
            assertTrue(bridge.ensureSupervisor())
            val old = release.publish(id, source, manifest)
            assertTrue(old.toString(), old.ok)
            val oldId = requireNotNull(old.deploymentId)
            for (count in 1..5) {
                val output = call(count)
                assertFalse(output.optBoolean("isError"))
                assertEquals(count.toString(), output.getJSONArray("content").getJSONObject(0).getString("text"))
                assertTrue(release.observe(id, true).ok)
            }
            assertEquals(ReleaseState.ACTIVE.name, release.readStatus(id).active!!.state)
            assertCard(oldId)
            coroutineScope {
                val first = async(Dispatchers.IO) { release.activateRelease(oldId, id) }
                val second = async(Dispatchers.IO) { release.activateRelease(oldId, id) }
                assertTrue(first.await().ok)
                assertTrue(second.await().ok)
            }
            val invalid = release.publish(id, "def broken(", manifest)
            assertFalse(invalid.ok)
            assertEquals(old.deploymentId, release.readStatus(id).active!!.deploymentId)
            assertEquals("1", call(1).getJSONArray("content").getJSONObject(0).getString("text"))
            val startupFailure = release.publish(id, source, manifest.copy(entrypoint = "missing"))
            assertFalse(startupFailure.ok)
            assertEquals(oldId, release.readStatus(id).active!!.deploymentId)
            assertEquals("1", call(1).getJSONArray("content").getJSONObject(0).getString("text"))
            val candidate = release.publish(id, source.replace("return value", "return value + 1"), manifest)
            assertTrue(candidate.toString(), candidate.ok)
            assertEquals(ReleaseState.CANARY, candidate.state)
            assertTrue(call("invalid").getBoolean("isError"))
            assertFalse(release.observe(id, false).ok)
            assertEquals(old.deploymentId, release.readStatus(id).active!!.deploymentId)
            assertEquals("1", call(1).getJSONArray("content").getJSONObject(0).getString("text"))
            assertCard(oldId)
            val mirror = entry.database().deploymentJournalDao().getActiveForModule(id)
            assertEquals(old.deploymentId, mirror!!.deploymentId)
            assertEquals(ReleaseState.ACTIVE.name, mirror.state)
            File(context.filesDir, "a6-production-release-report.json").writeText(JSONObject()
                .put("completed", true).put("fixture", id).put("active", old.deploymentId)
                .put("failedCandidate", candidate.deploymentId).put("cardViewModelMatchesAuthority", true)
                .put("roomMirrorMatchesAuthority", true).put("providerRequests", 0)
                .put("scope", "production_release_api_with_dedicated_fixture_not_rendered_UI").toString(2))
        } finally {
            bridge.supPost("/module/$id/stop", JSONObject())
            instrumentation.runOnMainSync { owner.clear() }
        }
    }
}
