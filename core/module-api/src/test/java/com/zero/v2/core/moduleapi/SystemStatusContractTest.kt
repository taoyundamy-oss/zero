package com.zero.v2.core.moduleapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemStatusContractTest {
    private fun snapshot() = SystemStateAssembler.assemble(
        SystemStateAssembler.SystemStateInputs(
            modules = emptyMap(), budget = SystemStateAssembler.BudgetInputs(0, 0, 0.0, 100, 10f),
            agentStateName = "IDLE", termux = SystemStateAssembler.TermuxInputs(true, 7878, 1, 0),
            profiles = emptyList(), memoryAvailable = StatusFact.UNKNOWN,
            knowledgeAvailable = StatusFact.UNKNOWN, indexVersion = null, retrievalBacklog = null,
            now = 100_000,
        ), 7,
    )

    @Test fun `alive without an observation is unknown`() {
        assertEquals(StatusFact.UNKNOWN, snapshot().termux.connection)
    }

    @Test fun `unrequested and never observed sections are not stale`() {
        val result = SystemStateAssembler.zeroStatus(snapshot(), setOf("runtime"), null, 10)
        assertTrue(result.stale.isEmpty())
        assertEquals(listOf("runtime.pendingJobs", "runtime.lastFailureCode"), result.unknown)
    }

    @Test fun `cursor is bound to section selection and invalid offsets are rejected`() {
        val s = snapshot()
        val first = SystemStateAssembler.zeroStatus(s, setOf("runtime", "voice"), null, 1)
        val changed = SystemStateAssembler.zeroStatus(s, setOf("termux", "voice"), null, 1, first.nextCursor)
        assertEquals(listOf("cursor_expired"), changed.unknown)
        for (bad in listOf("7:x", "7:-1", "7:99999", "7:0:extra")) {
            assertEquals(listOf("cursor_expired"), SystemStateAssembler.zeroStatus(s, setOf("runtime"), null, 1, bad).unknown)
        }
    }

    @Test fun `continuation is not swallowed by since revision`() {
        val s = snapshot()
        val sections = setOf("runtime", "voice")
        val first = SystemStateAssembler.zeroStatus(s, sections, null, 1)
        val next = SystemStateAssembler.zeroStatus(s, sections, 7, 1, first.nextCursor)
        assertEquals(setOf("voice"), next.facts.keys)
    }

    @Test fun `empty collection is an explicit observed result`() {
        val result = SystemStateAssembler.zeroStatus(snapshot(), setOf("modules", "models"), null, 1)
        assertEquals(mapOf("models" to emptyList<Any>()), result.facts)
        assertTrue(result.truncated)
    }

    @Test fun `future timestamps are unknown and ttl boundary is inclusive`() {
        assertEquals(StatusFact.UNKNOWN, StatusProjection.freshness(101, 100, 30))
        assertEquals(StatusFact.LIVE, StatusProjection.freshness(70, 100, 30))
        assertEquals(StatusFact.STALE, StatusProjection.freshness(69, 100, 30))
    }

    @Test fun `deployment ttl and failure never certify cached active version`() {
        val base = SystemStateAssembler.SystemStateInputs(emptyMap(),
            SystemStateAssembler.BudgetInputs(0, 0, 0.0, 100, 10f), "IDLE",
            SystemStateAssembler.TermuxInputs(true, 7878, 1, 99_000, deployments = listOf(
                ModuleDeploymentStatus("demo", ReleaseVersionStatus("id", "hash", "ACTIVE", 1),
                    emptyList(), 1))), emptyList(), StatusFact.UNKNOWN, StatusFact.UNKNOWN, null, null, 100_000)
        val stale = SystemStateAssembler.assemble(base, 1)
        assertEquals(StatusFact.STALE, stale.termux.deployments.single().freshness)
        assertTrue(SystemStateAssembler.zeroStatus(stale, setOf("termux"), null, 50)
            .stale.contains("termux.deployments.demo"))
        val failed = SystemStateAssembler.assemble(base.copy(termux = base.termux.copy(alive = false)), 2)
        assertEquals(StatusFact.UNKNOWN, failed.termux.deployments.single().freshness)
    }

    @Test fun `http response projection expires and never treats rejection as live`() {
        val base = snapshot()
        val input = SystemStateAssembler.SystemStateInputs(emptyMap(),
            SystemStateAssembler.BudgetInputs(0, 0, 0.0, 100, 10f), "IDLE",
            SystemStateAssembler.TermuxInputs(false, 7878, null), listOf(
                SystemStateAssembler.ProfileInput("ok", "CHAT", true, true, 100_000,
                    listOf("CHAT"), "chat_stream", "http_200", 200, 100_000),
                SystemStateAssembler.ProfileInput("bad", "CHAT", true, true, 100_000,
                    requestOutcome = "http_401", httpStatus = 401, requestObservedAt = 100_000)),
            StatusFact.UNKNOWN, StatusFact.UNKNOWN, null, null, base.capturedAt)
        val projected = SystemStateAssembler.assemble(input, 1)
        assertEquals(StatusFact.LIVE, projected.modelRoutes.first { it.profileId == "ok" }.availability)
        assertEquals(StatusFact.UNKNOWN, projected.modelRoutes.first { it.profileId == "bad" }.availability)
        val expired = SystemStateAssembler.assemble(input.copy(now = 130_001), 2)
        assertTrue(expired.modelRoutes.all { it.availability == StatusFact.STALE })
        assertTrue(SystemStateAssembler.zeroStatus(expired, setOf("models"), null, 10).stale.contains("models"))
        assertTrue(SystemStateAssembler.zeroStatus(projected, setOf("models"), null, 10)
            .facts.toString().contains("http_response_only"))
    }
}
