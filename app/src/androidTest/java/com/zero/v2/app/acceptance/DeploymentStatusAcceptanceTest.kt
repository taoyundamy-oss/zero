package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.moduleapi.StatusFact
import com.zero.v2.core.moduleapi.SystemStateAssembler
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Read-only production projection; no deployment actions or provider probes. */
class DeploymentStatusAcceptanceTest {
    @Test fun supervisorMetadataFlowsThroughProductionRepository() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java).systemState()
        withTimeout(60_000) {
            while (repository.snapshot().termux.deployments.isEmpty()) delay(500)
        }
        val snapshot = repository.snapshot()
        val termux = snapshot.termux
        assertEquals(StatusFact.LIVE, termux.connection)
        assertEquals(true, termux.rootfs)
        assertEquals(1, termux.releaseProtocol)
        assertFalse(termux.deploymentTruncated)
        assertEquals(termux.runtimeModules!!.map { it.id }.toSet(), termux.deployments.map { it.moduleId }.toSet())
        assertTrue(termux.deployments.all { it.freshness == StatusFact.LIVE && it.errorCode == null })
        val status = SystemStateAssembler.zeroStatus(snapshot, setOf("termux"), null, 100)
        assertFalse(status.truncated)
        val body = JSONObject(status.facts).toString()
        assertFalse(body.contains("endpoint"))
        assertFalse(body.contains("manifest"))
        assertFalse(body.contains("config"))
        File(context.filesDir, "a5-deployment-report.json").writeText(JSONObject()
            .put("completed", true).put("source", "production_repository_supervisor_read_only")
            .put("providerRequests", 0).put("termux", JSONObject(status.facts))
            .put("unknown", org.json.JSONArray(status.unknown)).toString(2))
    }
}
