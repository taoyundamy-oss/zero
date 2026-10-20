package com.zero.v2.services.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContractTest {
    private val base = LlmProfile("custom", "Custom", "https://example.test/v1", "ref", modelId = "wire")
    private fun body(profile: LlmProfile, stream: Boolean = true): JSONObject = JSONObject()
        .put("model", profile.modelId).put("temperature", profile.temperature).put("max_tokens", profile.maxTokens)
        .also { ModelContracts.get(profile.contractId).apply(it, profile, stream) }

    @Test fun `dashscope uses HTTP top level and distinguishes unset false and invalid zero`() {
        val profile = base.copy(contractId = "dashscope-v1")
        assertFalse(body(profile).has("enable_thinking"))
        val disabled = body(profile.copy(parameters = mapOf("thinking" to "false")))
        assertFalse(disabled.getBoolean("enable_thinking"))
        assertFalse(disabled.has("extra_body"))
        assertNotNull(ModelContracts.validationError(profile.copy(parameters = mapOf("budget" to "0"))))
        assertNotNull(ModelContracts.validationError(profile.copy(parameters = mapOf("thinking" to "maybe"))))
    }

    @Test fun `deepseek emits consistent sync and stream options and legacy sync remains explicit`() {
        val profile = base.copy(contractId = "deepseek-v1",
            parameters = mapOf("thinking" to "true", "effort" to "high"))
        for (stream in listOf(true, false)) {
            assertEquals("enabled", body(profile, stream).getJSONObject("thinking").getString("type"))
            assertEquals("high", body(profile, stream).getString("reasoning_effort"))
        }
        assertFalse(body(profile.copy(contractId = "deepseek-legacy-v1"), false).has("thinking"))
        assertTrue(ModelContracts.get(profile.contractId).replayReasoning)
    }

    @Test fun `mimo thinking is explicit sync stream consistent and sampling is omitted`() {
        val profile = base.copy(contractId = "mimo-v1", parameters = mapOf("thinking" to "true"))
        for (stream in listOf(true, false)) {
            val request = body(profile, stream)
            assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
            assertFalse(request.has("temperature"))
            assertFalse(request.has("reasoning_effort"))
        }
        val contract = ModelContracts.get(profile.contractId)
        assertTrue(contract.replayReasoning)
        assertEquals("disabled", body(contract.bounded(profile)).getJSONObject("thinking").getString("type"))
        assertFalse(body(profile.copy(parameters = emptyMap())).has("thinking"))
        assertNotNull(ModelContracts.validationError(profile.copy(parameters = mapOf("effort" to "high"))))
        val transcript = ChatRequestBody.build(profile, null, listOf(
            com.zero.v2.core.common.ChatMessage(role = com.zero.v2.core.common.MessageRole.ASSISTANT, content = null,
                thinking = "opaque reasoning",
                toolCalls = listOf(com.zero.v2.core.common.ToolCall("id", "probe", "{}"))),
        ), true, emptyList()).getJSONArray("messages").getJSONObject(0)
        assertEquals("opaque reasoning", transcript.getString("reasoning_content"))
        assertEquals("id", transcript.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test fun `unknown keys contracts conflicting options ranges and context fail before request`() {
        assertNotNull(ModelContracts.validationError(base.copy(contractId = "future-v99")))
        assertNotNull(ModelContracts.validationError(base.copy(parameters = mapOf("model" to "override"))))
        assertNotNull(ModelContracts.validationError(base.copy(maxTokens = base.contextWindow)))
        assertNotNull(ModelContracts.validationError(base.copy(temperature = Float.NaN)))
        assertNotNull(ModelContracts.validationError(base.copy(contractId = "dashscope-v1",
            parameters = mapOf("thinking" to "false", "budget" to "1024"))))
        assertNotNull(ModelContracts.validationError(base.copy(contractId = "deepseek-v1",
            parameters = mapOf("effort" to "ultra"))))
    }

    @Test fun `gateway and arbitrary profile identity do not determine parameter dialect`() {
        val profile = base.copy(id = "qwen", cloudflareByok = true, contractId = "deepseek-v1",
            parameters = mapOf("thinking" to "false"))
        val request = body(profile)
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.has("enable_thinking"))
        assertEquals("wire", request.getString("model"))
    }

    @Test fun `local template and completion token dialects compile correctly`() {
        val local = base.copy(contractId = "template-thinking-v1", parameters = mapOf("thinking" to "false"))
        assertFalse(body(local).getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        val effort = body(base.copy(contractId = "effort-v1", parameters = mapOf("effort" to "medium")))
        assertEquals(base.maxTokens, effort.getInt("max_completion_tokens"))
        assertFalse(effort.has("max_tokens"))
        assertFalse(effort.has("temperature"))
    }

    @Test fun `bounded override disables reasoning and does not mutate configured parameters`() {
        val original = base.copy(contractId = "deepseek-v1",
            parameters = mapOf("thinking" to "true", "effort" to "max"))
        val bounded = ModelContracts.get(original.contractId).bounded(original.copy(maxTokens = 128))
        assertEquals(mapOf("thinking" to "false"), bounded.parameters)
        assertEquals("max", original.parameters["effort"])
        assertNull(ModelContracts.validationError(bounded))
    }

    @Test fun `old provider slots import once with endpoint guard and preserve scalar values`() {
        val old = base.copy(id = "qwen", baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1")
        val raw = LlmProfileJson.toJson(old).also { it.remove("contractId"); it.remove("parameters") }
        val migrated = LlmProfileJson.fromJson(raw)
        assertEquals("dashscope-v1", migrated.contractId)
        assertEquals(old.thinkingBudget.toString(), migrated.parameters["budget"])
        raw.put("baseUrl", "https://api.cloudflare.com/client/v4/accounts/account/ai/v1")
        assertEquals("generic-v1", LlmProfileJson.fromJson(raw).contractId)
        assertEquals(migrated, LlmProfileJson.fromJson(LlmProfileJson.toJson(migrated)))
    }

    @Test fun `version one snapshot migrates once and preserves original model identity`() {
        val raw = JSONObject(ProfileSnapshot.encode(listOf(base))).put("schemaVersion", 1)
        raw.getJSONArray("profiles").getJSONObject(0).remove("contractId")
        var saved = ""
        val owner = ProfilePersistence(raw.toString(), null) { saved = it; true }
        assertNull(owner.error)
        assertEquals(base.modelId, owner.profiles.single().modelId)
        assertTrue(ProfileSnapshot.isCurrent(saved))
    }
}
