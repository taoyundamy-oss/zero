package com.zero.v2.framework.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2：TokenBudgetAllocator 全预算模型（设计§4.3）。
 * 验收：长历史+多工具+图像下请求不超模型预算；各段硬上限（证据≤8%/风格≤120/摘要≤800/工具≤15%）。
 */
class TokenBudgetAllocatorTest {

    private val alloc = TokenBudgetAllocator()

    @Test fun `opaque provider state contributes to history budget without duplicating visible content`() {
        val message = com.zero.v2.core.common.ChatMessage(role = com.zero.v2.core.common.MessageRole.ASSISTANT,
            content = "OK", providerContent = com.zero.v2.core.common.ProviderContentState("scope", "x".repeat(8000)))
        assertTrue(alloc.estimateMessageTokens(message) >= 4000)
        assertTrue(alloc.estimateMessageTokens(message.copy(providerContent = null)) < 10)
    }

    @Test fun `local history reserves actual output stable prompt and tools`() {
        val budget = alloc.historyInputBudget(512_000, 24_576, 2048, "中".repeat(4000), "t".repeat(2000))
        assertTrue(budget in 1..17_000)
        assertTrue(budget + 4000 + 1000 + 2048 < 24_576)
    }

    @Test fun `cloud generation ceiling remains unchanged below model limit`() {
        assertTrue(alloc.historyInputBudget(512_000, 1_048_576, 4096, "stable", "tools") == 512_000)
    }

    @Test
    fun `available input subtracts output reserve and safety margin`() {
        val b = alloc.allocateFull(modelContextWindow = 100_000, outputReserve = 4_000, safetyMarginRatio = 0.05)
        assertTrue(b.availableInput <= 100_000 - 4_000 - 5_000 + 1) // 100k - 4k - 5k = 91k
        assertTrue(b.outputReserve == 4_000)
        assertTrue(b.safetyMargin == 5_000)
    }

    @Test
    fun `segments do not exceed model budget`() {
        // 长历史 + 多工具 + 图像场景：各段总和 ≤ availableInput
        val b = alloc.allocateFull(modelContextWindow = 1_000_000, outputReserve = 8_192, safetyMarginRatio = 0.05)
        assertTrue("total=${b.totalAllocated} avail=${b.availableInput}", b.totalAllocated <= b.availableInput)
    }

    @Test
    fun `caps enforced for evidence style summary and tools`() {
        val b = alloc.allocateFull(modelContextWindow = 100_000, outputReserve = 4_000, safetyMarginRatio = 0.05)
        val avail = b.availableInput
        assertTrue("evidence ${b.evidenceMax} > 8% $avail", b.evidenceMax <= avail * 0.08 + 1)
        assertTrue("style ${b.styleMax} > 120", b.styleMax <= 120)
        assertTrue("summary ${b.summaryMax} > 800", b.summaryMax <= 800)
        assertTrue("tools ${b.toolsDefinition} > 15% $avail", b.toolsDefinition <= avail * 0.15 + 1)
    }

    @Test
    fun `history non negative even with tiny context`() {
        val b = alloc.allocateFull(modelContextWindow = 1_000, outputReserve = 4_096, safetyMarginRatio = 0.05)
        assertTrue(b.history >= 0)
        assertTrue(b.availableInput >= 0)
    }

    @Test
    fun `legacy allocate returns full budget with same persona and tools fields`() {
        val legacy = alloc.allocate(contextWindow = 512_000)
        assertTrue(legacy.systemAndPersona > 0)
        assertTrue(legacy.toolsDefinition > 0)
        assertTrue(legacy.availableInput <= 512_000)
    }
}
