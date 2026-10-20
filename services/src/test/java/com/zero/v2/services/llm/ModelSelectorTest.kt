package com.zero.v2.services.llm

import com.zero.v2.core.common.InputModality
import com.zero.v2.core.common.ModelRequirements
import com.zero.v2.core.common.StreamChunk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2：ModelSelector 能力路由（落地计划 M2 验收）。
 */
class ModelSelectorTest {
    @Test fun `model only routing preserves capability and context selection`() {
        val s = selector(deepseek, qwen, qwenVl)
        val requests = listOf(
            ModelRequirements(input = setOf(InputModality.TEXT)),
            ModelRequirements(input = setOf(InputModality.IMAGE), needsTools = true),
            ModelRequirements(input = setOf(InputModality.AUDIO)),
            ModelRequirements(input = setOf(InputModality.VIDEO)),
            ModelRequirements(input = setOf(InputModality.TEXT), minContextTokens = 2_000_000),
        )
        requests.forEach { assertEquals(s.select(it).model, s.selectModel(it)) }
    }

    @Test fun `model only routing observes registration and removal without caching`() {
        val s = ModelSelector()
        val req = ModelRequirements(input = setOf(InputModality.TEXT))
        assertEquals(null, s.selectModel(req))
        s.register(qwen)
        assertEquals(qwen, s.selectModel(req))
        s.register(deepseek)
        assertEquals(deepseek, s.selectModel(req))
        s.unregister(deepseek.modelId)
        assertEquals(qwen, s.selectModel(req))
        s.unregister(qwen.modelId)
        assertEquals(null, s.selectModel(req))
    }

    @Test fun `legacy chat routing respects priority when vision capabilities are added`() {
        val profile = LlmProfile.defaults().first { it.id == "deepseek" }
        val preferred = model(profile.modelId, profile.capabilities, 100)
        assertEquals(preferred, selector(preferred, qwen).select(setOf(ModelCapability.CHAT)))
    }
    @Test fun `current DeepSeek preset routes images without a separate vision provider`() {
        val profile = LlmProfile.defaults().first { it.id == "deepseek" }
        val result = selector(model(profile.modelId, profile.capabilities, profile.priority))
            .select(ModelRequirements(input = setOf(InputModality.TEXT, InputModality.IMAGE), needsTools = true))
        assertTrue("Official Flash preset must advertise its image input capability", result.isSelected)
        assertEquals(profile.modelId, result.model?.modelId)
    }

    @Test fun `image capability alone must never satisfy audio input`() {
        assertFalse(selector(qwenVl).select(ModelRequirements(input = setOf(InputModality.AUDIO))).isSelected)
        assertFalse(selector(qwenVl).select(ModelRequirements(input = setOf(InputModality.VIDEO))).isSelected)
    }


    /** 假模型——参数名加 Arg 后缀，避免属性初始化器 `override val x = x` 自引用遮蔽。 */
    @Suppress("LongParameterList")  // 测试 fixture builder——多参数构造测试模型必然
    private fun model(
        idArg: String,
        capsArg: Set<ModelCapability>,
        priorityArg: Int = 0,
        enabledArg: Boolean = true,
        keyConfiguredArg: Boolean = true,
        contextWindowArg: Int = 1_000_000,
    ): ILanguageModel = object : ILanguageModel {
        override val modelId = idArg
        override val displayName = idArg
        override val contextWindow = contextWindowArg
        override val capabilities = capsArg
        override val keyConfigured = keyConfiguredArg
        override val priority = priorityArg
        override val enabled = enabledArg
        override fun chatStream(
            messages: List<com.zero.v2.core.common.ChatMessage>,
            systemPrompt: String?,
            functions: List<com.zero.v2.core.common.FunctionDefinition>,
            prefixVersion: String?,
        ) = flowOf(StreamChunk(contentDelta = "ok", isFinal = true))
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = "ok"
    }

    private fun selector(vararg models: ILanguageModel): ModelSelector =
        ModelSelector().apply { models.forEach { register(it) } }

    private val deepseek = model(
        "deepseek-v4-flash",
        setOf(
            ModelCapability.CHAT, ModelCapability.REASONING,
            ModelCapability.FUNCTION_CALLING, ModelCapability.LONG_CONTEXT,
        ),
        priorityArg = 100,
    )
    private val qwen = model(
        "qwen3.8-max",
        setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING, ModelCapability.LONG_CONTEXT),
        priorityArg = 90,
    )
    private val qwenVl = model(
        "qwen3-vl-flash",
        setOf(
            ModelCapability.CHAT, ModelCapability.IMAGE_INPUT,
            ModelCapability.VISION, ModelCapability.FUNCTION_CALLING,
        ),
        priorityArg = 50,
    )

    // ── 验收：纯文本仍选已有 DeepSeek/Qwen ──

    @Test
    fun `text requirement selects highest priority chat model`() {
        val s = selector(deepseek, qwen, qwenVl)
        val r = s.select(ModelRequirements(input = setOf(InputModality.TEXT)))
        assertTrue(r.isSelected)
        assertEquals("deepseek-v4-flash", r.model?.modelId) // priority 100 > qwen 90
    }

    @Test
    fun `text with tools requires function calling`() {
        val s = selector(deepseek, qwen)
        val r = s.select(ModelRequirements(input = setOf(InputModality.TEXT), needsTools = true))
        assertTrue(r.isSelected)
        assertEquals("deepseek-v4-flash", r.model?.modelId)
    }

    // ── 验收：图像选明确支持原生图的 profile ──

    @Test
    fun `image requirement selects vision profile with IMAGE_INPUT`() {
        val s = selector(deepseek, qwen, qwenVl)
        val r = s.select(ModelRequirements(input = setOf(InputModality.IMAGE)))
        assertTrue(r.isSelected)
        assertEquals("qwen3-vl-flash", r.model?.modelId)
    }

    @Test
    fun `image requirement does NOT conflate CHAT with IMAGE_INPUT`() {
        val s = selector(deepseek)
        val r = s.select(ModelRequirements(input = setOf(InputModality.IMAGE)))
        assertFalse(r.isSelected)
        assertTrue(r.reason.contains("IMAGE_INPUT"))
    }

    // ── 验收：缺能力解释回退 ──

    @Test
    fun `no match returns reason explaining missing capability`() {
        val s = selector(deepseek, qwen)
        val r = s.select(ModelRequirements(input = setOf(InputModality.IMAGE)))
        assertFalse(r.isSelected)
        assertTrue("reason 应含 no_match", r.reason.contains("no_match"))
        assertTrue("reason 应含缺失能力", r.reason.contains("IMAGE_INPUT"))
    }

    // ── 验收：禁用/缺 Key 不入候选 ──

    @Test
    fun `disabled model excluded from candidates`() {
        val disabled = model("disabled-chat", setOf(ModelCapability.CHAT), enabledArg = false, priorityArg = 999)
        val s = selector(disabled, deepseek)
        val r = s.select(ModelRequirements(input = setOf(InputModality.TEXT)))
        assertTrue(r.isSelected)
        assertEquals("deepseek-v4-flash", r.model?.modelId)
    }

    @Test
    fun `model without key excluded from candidates`() {
        val noKey = model("nokey-chat", setOf(ModelCapability.CHAT), priorityArg = 999, keyConfiguredArg = false)
        val s = selector(noKey, deepseek)
        val r = s.select(ModelRequirements(input = setOf(InputModality.TEXT)))
        assertEquals("deepseek-v4-flash", r.model?.modelId)
    }

    @Test
    fun `no match reason reports disabled and noKey counts`() {
        val disabled = model("d", setOf(ModelCapability.CHAT), enabledArg = false)
        val noKey = model("k", setOf(ModelCapability.CHAT), keyConfiguredArg = false)
        val s = selector(disabled, noKey)
        val r = s.select(ModelRequirements(input = setOf(InputModality.IMAGE)))
        assertFalse(r.isSelected)
        assertTrue(r.reason.contains("disabled=1"))
        assertTrue(r.reason.contains("noKey=1"))
    }

    // ── 验收：不因 modelId 含 vl 自动声明能力 ──

    @Test
    fun `modelId containing vl but no IMAGE_INPUT capability is NOT selected for image`() {
        val fakeVl = model("some-vl-nocap", setOf(ModelCapability.CHAT), priorityArg = 999)
        val s = selector(fakeVl)
        val r = s.select(ModelRequirements(input = setOf(InputModality.IMAGE)))
        assertFalse(r.isSelected)
    }

    // ── tie-break 确定性 ──

    @Test
    fun `equal priority ties break by context window then modelId`() {
        val a = model("aaa", setOf(ModelCapability.CHAT), priorityArg = 10, contextWindowArg = 200_000)
        val b = model("bbb", setOf(ModelCapability.CHAT), priorityArg = 10, contextWindowArg = 300_000)
        val s = selector(a, b)
        val r = s.select(ModelRequirements(input = setOf(InputModality.TEXT)))
        assertEquals("bbb", r.model?.modelId) // 同 priority → contextWindow 大者
    }

    @Test
    fun `min context tokens filters candidates`() {
        val small = model("small", setOf(ModelCapability.CHAT), priorityArg = 999, contextWindowArg = 50_000)
        val big = model("big", setOf(ModelCapability.CHAT), priorityArg = 1, contextWindowArg = 500_000)
        val s = selector(small, big)
        val r = s.select(ModelRequirements(input = setOf(InputModality.TEXT), minContextTokens = 100_000))
        assertEquals("big", r.model?.modelId)
    }

    // ── 旧 select(Set) 迁移兼容 ──

    @Test
    fun `legacy select by capability set still works with key filter`() {
        val s = selector(deepseek, qwenVl)
        val m = s.select(setOf(ModelCapability.CHAT))
        assertNotNull(m)
    }

    @Test
    fun `legacy select excludes no-key model`() {
        val noKey = model("nokey", setOf(ModelCapability.CHAT), priorityArg = 999, keyConfiguredArg = false)
        val s = selector(noKey, deepseek)
        val m = s.select(setOf(ModelCapability.CHAT))
        assertEquals("deepseek-v4-flash", m?.modelId)
    }
}
