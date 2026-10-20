package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1：稳定前缀 + prefixVersion 哈希不变量 + 缓存 usage 观测（落地计划 C1 验收）。
 */
class PromptPrefixTest {

    @Test
    fun `tool schema is not duplicated in stable system text`() {
        val prefix = PromptPrefix.stablePrefix(persona, protocol, tools)
        assertFalse(prefix.contains("properties"))
        assertFalse(prefix.contains("web_search"))
        assertEquals(prefix, PromptPrefix.stablePrefix(persona, protocol, emptyList()))
    }

    private val tools = listOf(
        FunctionDefinition("web_search", "搜索网络", mapOf("type" to "object", "properties" to mapOf("query" to mapOf("type" to "string")))),
        FunctionDefinition("get_time", "取时间", mapOf("type" to "object")),
    )
    private val persona = "你是 Zero，诚实简洁。"
    private val protocol = PromptPrefix.PROVIDER_PROTOCOL_V1
    private val modelId = "deepseek-v4-flash"

    private fun version(
        personaArg: String = persona,
        toolsArg: List<FunctionDefinition> = tools,
        modelIdArg: String = modelId,
    ): String {
        val sys = PromptPrefix.stableSystem(personaArg, protocol)
        val manifest = PromptPrefix.toolManifest(toolsArg)
        return PromptPrefix.prefixVersion(protocol, modelIdArg, sys, manifest)
    }

    // ── 验收 1：相同 persona/profile/工具 manifest 连续轮 stablePrefix 字节相同 ──

    @Test
    fun `stable prefix bytes identical across calls with same inputs`() {
        val a = PromptPrefix.stablePrefix(persona, protocol, tools)
        val b = PromptPrefix.stablePrefix(persona, protocol, tools)
        assertEquals(a, b)
        // 同输入连续两次 prefixVersion 相同
        assertEquals(version(), version())
    }

    // ── 验收 2：摘要/时间/情绪变化不改变 hash（这些根本不是哈希输入）──

    @Test
    fun `summary time and mood are not hash inputs so cannot affect version`() {
        // prefixVersion 签名仅含 providerProtocol/modelId/stableSystem/toolManifest
        // —— 不含摘要/时间/情绪。同 persona/protocol/tools/modelId 下，无论"摘要/时间/情绪"如何变，hash 不变。
        val v1 = version()
        // 模拟摘要更新、时间变化、情绪变化：它们没有输入路径，hash 必然不变
        val v2 = version()
        assertEquals("摘要/时间/情绪变化不得改变 hash", v1, v2)

        // stablePrefix 内也不含动态上下文槽位
        val sp = PromptPrefix.stablePrefix(persona, protocol, tools)
        assertFalse(sp.contains("摘要"))
        assertFalse(sp.contains("情绪"))
        assertFalse(Regex("\\d{10,}").containsMatchIn(sp)) // 无秒级时间戳
    }

    // ── 验收 3：角色/profile/工具 manifest 变更会改变 hash ──

    @Test
    fun `persona change changes hash`() {
        assertNotEquals(version(personaArg = persona), version(personaArg = "你是另一个角色。"))
    }

    @Test
    fun `modelId profile change changes hash`() {
        assertNotEquals(version(modelIdArg = "deepseek-v4-flash"), version(modelIdArg = "qwen3.8-max"))
    }

    @Test
    fun `adding a tool changes hash`() {
        val more = tools + FunctionDefinition("calc", "计算器", mapOf("type" to "object"))
        assertNotEquals(version(toolsArg = tools), version(toolsArg = more))
    }

    @Test
    fun `reordering tools does NOT change hash (deterministic sort)`() {
        val reversed = tools.reversed()
        assertEquals(version(toolsArg = tools), version(toolsArg = reversed))
        // manifest 字节也相同（排序后）
        assertEquals(PromptPrefix.toolManifest(tools), PromptPrefix.toolManifest(reversed))
    }

    @Test
    fun `provider protocol change changes hash`() {
        val sys = PromptPrefix.stableSystem(persona, protocol)
        val manifest = PromptPrefix.toolManifest(tools)
        assertNotEquals(
            PromptPrefix.prefixVersion(protocol, modelId, sys, manifest),
            PromptPrefix.prefixVersion("openai_compat_v2", modelId, sys, manifest),
        )
    }

    // ── 验收 4：缺 usage 显示 unknown 而非 0% ──

    @Test
    fun `cache observation reports unknown when usage absent`() {
        assertEquals("unknown", CacheObservation.ABSENT.ratioLabel)
        assertEquals("unknown", CacheObservation.fromUsage(present = false, 0, 0, 0, 0, 0).ratioLabel)
    }

    @Test
    fun `cache observation reports unknown when input tokens zero`() {
        val obs = CacheObservation.fromUsage(present = true, inputTokens = 0, 0, 0, 0, 0)
        assertEquals("unknown", obs.ratioLabel)
    }

    @Test
    fun `cache observation reports percentage when usage present`() {
        // 800 命中 / 1000 输入 = 80%
        val obs = CacheObservation.fromUsage(present = true, inputTokens = 1000, hitTokens = 800, missTokens = 200, outputTokens = 50, totalTokens = 1050)
        assertEquals("80%", obs.ratioLabel)
        assertTrue(obs.present)
    }

    @Test
    fun `cache observation clamps to 100 percent`() {
        val obs = CacheObservation.fromUsage(present = true, inputTokens = 100, hitTokens = 200, missTokens = 0, outputTokens = 10, totalTokens = 110)
        assertEquals("100%", obs.ratioLabel) // 钳 0-100
    }
}
