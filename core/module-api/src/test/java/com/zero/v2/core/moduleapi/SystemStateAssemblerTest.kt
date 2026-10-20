package com.zero.v2.core.moduleapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNull

/**
 * S1：SystemStateAssembler 纯逻辑单测——组装/脱敏/brief 长度/zero_status sections/unknown/stale。
 */
class SystemStateAssemblerTest {
    @Test fun `unobserved queue and retrieval counts remain unknown rather than zero`() {
        val snapshot = SystemStateAssembler.assemble(inputs().copy(retrievalBacklog = null), 1)
        val result = SystemStateAssembler.zeroStatus(snapshot, setOf("runtime", "retrieval"), null, 50)
        assertEquals(null, snapshot.runtime.pendingJobs)
        assertEquals(null, snapshot.retrieval.backlog)
        assertTrue(result.unknown.containsAll(listOf("runtime.pendingJobs", "retrieval.backlog", "retrieval.indexVersion")))
    }

    @Test fun `resource permissions and backlog retain owner observations`() {
        val input = inputs().copy(pendingJobs = 3, retrievalBacklog = 7, retrievalObservedAt = 900_000L,
            resources = ResourceStatus("low", true, "high", false, 900_000L),
            permissions = listOf(PermissionStatus("camera", false, observedAt = 900_000L)))
        val snapshot = SystemStateAssembler.assemble(input, 2)
        val result = SystemStateAssembler.zeroStatus(snapshot, setOf("resources", "permissions", "retrieval"), null, 50)
        assertEquals(3, snapshot.runtime.pendingJobs)
        assertEquals(7, snapshot.retrieval.backlog)
        assertEquals(900_000L, snapshot.retrieval.observedAt)
        assertTrue(result.stale.containsAll(listOf("resources", "permissions", "retrieval")))
        assertTrue(snapshot.permissions.single().requiresUserAction)
    }

    @Test
    fun `pagination obeys limit and rejects a cursor from another revision`() {
        val snapshot = SystemStateAssembler.assemble(inputs(), revision = 7)
        val first = SystemStateAssembler.zeroStatus(snapshot, setOf("termux", "voice", "runtime"), null, 1)
        assertEquals(1, first.facts.size)
        assertTrue(first.truncated)
        val second = SystemStateAssembler.zeroStatus(snapshot, setOf("termux", "voice", "runtime"), null, 1, first.nextCursor)
        assertTrue(first.facts.keys.intersect(second.facts.keys).isEmpty())
        val expired = SystemStateAssembler.zeroStatus(snapshot.copy(revision = 8), setOf("termux"), null, 1, first.nextCursor)
        assertEquals(listOf("cursor_expired"), expired.unknown)
    }

    @Test
    fun `cached supervisor observation becomes stale without forging a new timestamp`() {
        val input = inputs().copy(now = 100000L, termux = SystemStateAssembler.TermuxInputs(true, 7878, 123, 1L))
        val snapshot = SystemStateAssembler.assemble(input, 1)
        assertEquals(StatusFact.STALE, snapshot.termux.connection)
        assertEquals(1L, snapshot.termux.observedAt)
    }

    private fun inputs(
        modules: Map<String, ModuleRuntimeState> = emptyMap(),
        termuxAlive: Boolean = false,
        profiles: List<SystemStateAssembler.ProfileInput> = emptyList(),
        budget: SystemStateAssembler.BudgetInputs = SystemStateAssembler.BudgetInputs(0, 0, 0.0, 20_000_000L, 10.0f),
        memoryAvailable: StatusFact = StatusFact.UNKNOWN,
        now: Long = 1_000_000L,
    ) = SystemStateAssembler.SystemStateInputs(
        modules = modules, budget = budget, agentStateName = "IDLE",
        termux = SystemStateAssembler.TermuxInputs(alive = termuxAlive, port = 7878,
            pid = if (termuxAlive) 1234 else null, observedAt = if (termuxAlive) now else 0L),
        profiles = profiles, memoryAvailable = memoryAvailable, knowledgeAvailable = StatusFact.UNKNOWN,
        indexVersion = null, retrievalBacklog = 0, now = now,
    )

    @Test
    fun `snapshot projects modules operational and termux unknown on dead probe`() {
        val s = SystemStateAssembler.assemble(inputs(
            modules = mapOf("chatbot" to ModuleRuntimeState(true, ModuleLifecycle.STARTED)),
            termuxAlive = false,
        ), revision = 1)
        assertEquals(1, s.modules.size)
        assertTrue(s.modules.first().operational)
        // 探活失败 = UNKNOWN，不断言离线
        assertEquals(StatusFact.UNKNOWN, s.termux.connection)
    }

    @Test
    fun `model routes do not leak key value only configured flag`() {
        val s = SystemStateAssembler.assemble(inputs(
            profiles = listOf(
                SystemStateAssembler.ProfileInput("deepseek", "CHAT", enabled = true, keyConfigured = true),
                SystemStateAssembler.ProfileInput("qwen", "CHAT", enabled = false, keyConfigured = false),
            ),
        ), revision = 2)
        // 输出只含 profileId/kind/enabled/availability，无 Key/baseUrl
        val route = s.modelRoutes.first { it.profileId == "deepseek" }
        assertEquals("CHAT", route.kind)
        assertEquals(StatusFact.UNKNOWN, route.availability)
        assertEquals(true, route.keyConfigured)
        assertEquals(0L, route.observedAt)
        assertFalse(route.toString().contains("apiKey") || route.toString().contains("sk-"))
    }

    @Test
    fun `budget exposes class not exact amount`() {
        val s = SystemStateAssembler.assemble(inputs(
            budget = SystemStateAssembler.BudgetInputs(25_000_000L, 0, 0.0, 20_000_000L, 10.0f),
        ), revision = 3)
        assertEquals("exceeded", s.budgets.remainingClass)
        assertFalse(s.budgets.toString().contains("25000000"))
    }

    @Test
    fun `unobserved credentials and provider health remain unknown in status output`() {
        val snapshot = SystemStateAssembler.assemble(inputs(profiles = listOf(
            SystemStateAssembler.ProfileInput("local", "CHAT", true, null),
        )), revision = 1)
        assertNull(snapshot.modelRoutes.single().keyConfigured)
        val status = SystemStateAssembler.zeroStatus(snapshot, setOf("models"), null, 20)
        assertTrue(status.unknown.contains("models.availability"))
        assertFalse(status.facts.toString().contains("LIVE"))
    }

    @Test
    fun `brief stays within 200 token budget`() {
        val modules = (1..40).associate { "mod_$it" to ModuleRuntimeState(true, ModuleLifecycle.STARTED) }
        val s = SystemStateAssembler.assemble(inputs(modules = modules, termuxAlive = true), revision = 4)
        val b = SystemStateAssembler.brief(s)
        // 200 token ≈ 800 字符上限（估算 (chars+3)/4）
        assertTrue("brief 过长: ${b.length}", b.length <= 800)
        assertTrue(b.contains("modules:40/40"))
        assertTrue(b.contains("termux:ok"))
    }

    @Test
    fun `zero_status returns requested sections and unknown for unrecognized`() {
        val s = SystemStateAssembler.assemble(inputs(termuxAlive = true), revision = 5)
        val r = SystemStateAssembler.zeroStatus(s, setOf("termux", "brief", "nope"), null, 50)
        assertTrue(r.facts.containsKey("termux"))
        assertTrue(r.facts.containsKey("brief"))
        assertEquals(setOf("nope", "termux.sdkVersion", "termux.dynamicModules", "termux.rootfs",
            "termux.releaseProtocol"), r.unknown.toSet())
    }

    @Test
    fun `zero_status sinceRevision gates stale revision`() {
        val s = SystemStateAssembler.assemble(inputs(), revision = 5)
        val r = SystemStateAssembler.zeroStatus(s, setOf("termux"), sinceRevision = 5, limit = 50)
        assertTrue(r.facts.isEmpty())
    }

    @Test
    fun `stale fields marked when observation exceeds ttl`() {
        // 快照 capturedAt=now；runtime observedAt=now；人为构造过期快照
        val s = SystemStateAssembler.assemble(inputs(now = 1_000L), revision = 6).copy(capturedAt = 1_000_000L)
        val r = SystemStateAssembler.zeroStatus(s, setOf("runtime"), null, 50)
        assertTrue("runtime 应标 stale", r.stale.contains("runtime"))
    }

    @Test
    fun `voice section projects active state and appears in brief`() {
        // 默认 inactive
        val idle = SystemStateAssembler.assemble(inputs(), revision = 7)
        assertFalse(idle.voice.active)
        assertEquals("IDLE", idle.voice.state)
        assertFalse(SystemStateAssembler.brief(idle).contains("voice"))

        // 活跃语音会话
        val active = SystemStateAssembler.assemble(
            inputs().copy(voice = SystemStateAssembler.VoiceInputs(active = true, state = "LISTENING")),
            revision = 8,
        )
        assertTrue(active.voice.active)
        assertTrue(SystemStateAssembler.brief(active).contains("voice:listening"))

        val r = SystemStateAssembler.zeroStatus(active, setOf("voice"), null, 50)
        val voiceFact = r.facts["voice"] as? Map<*, *>
        assertEquals(true, voiceFact?.get("active"))
        assertEquals("LISTENING", voiceFact?.get("state"))
    }
}
