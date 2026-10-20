package com.zero.v2.services.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderTemplatesTest {
    private val base = LlmProfile.defaults().first().copy(contractId = "generic-v1", parameters = emptyMap())
    private val root = "https://api.cloudflare.com/client/v4/accounts/account/ai/v1"

    @Test fun `workers exact model and gateway routing are explicit`() {
        val p = base.copy(baseUrl = root, gatewayMode = GatewayMode.CF_WORKERS, modelId = "@cf/qwen/model-27b")
        assertNull(CloudflareChatTransport.validationError(p))
        val direct = CloudflareChatTransport.request(p, "token").build()
        assertEquals("Bearer token", direct.header("Authorization"))
        assertNull(direct.header("cf-aig-gateway-id"))
        val gateway = CloudflareChatTransport.request(p.copy(cloudflareGatewayId = "zero"), "token").build()
        assertEquals("zero", gateway.header("cf-aig-gateway-id"))
        for (bad in listOf("qwen-model", "workers-ai/@cf/qwen/model", "@cf/qwen/model bad")) {
            assertNotNull(CloudflareChatTransport.validationError(p.copy(modelId = bad)))
        }
        assertNotNull(CloudflareChatTransport.validationError(p.copy(authMode = ConnectionAuth.NONE)))
        assertFalse(CloudflareChatTransport.credentialReady(p.copy(authMode = ConnectionAuth.NONE), null))
        val legacy = p.copy(gatewayMode = GatewayMode.DIRECT, cloudflareByok = true, cloudflareGatewayId = "old")
        assertEquals("old", CloudflareChatTransport.request(legacy, "token").build().header("cf-aig-gateway-id"))
    }

    @Test fun `gateway billing choice does not rewrite models or inject provider parameters`() {
        for (mode in listOf(GatewayMode.CF_BYOK, GatewayMode.CF_GATEWAY)) {
            val p = base.copy(baseUrl = root, modelId = "google/exact-model",
                gatewayMode = mode, cloudflareGatewayId = "default")
            val request = CloudflareChatTransport.request(p, "token").build()
            assertEquals("default", request.header("cf-aig-gateway-id"))
            val body = JSONObject().put("model", p.modelId)
            CloudflareChatTransport.applyContract(body, p, true)
            assertEquals("google/exact-model", body.getString("model"))
            assertFalse(body.has("enable_thinking"))
            assertNull(request.header("cf-aig-byok-alias"))
        }
        assertNotNull(CloudflareChatTransport.validationError(base.copy(baseUrl = root,
            modelId = "@cf/qwen/model", gatewayMode = GatewayMode.CF_BYOK, cloudflareGatewayId = "default")))
    }

    @Test fun `templates isolate credentials and preserve model contracts and capabilities`() {
        assertEquals(ProviderTemplates.all.size, ProviderTemplates.all.map { it.id }.distinct().size)
        for (template in ProviderTemplates.all) {
            val p = template.connection("fresh-${template.id}").project(base)
            assertNotEquals(base.apiKeyRef, p.apiKeyRef)
            assertNull(p.apiKeyRefFallback)
            assertEquals(base.modelId, p.modelId)
            assertEquals(base.parameters, p.parameters)
            assertEquals(base.capabilities, p.capabilities)
            assertEquals(base.contractId, p.contractId)
            assertEquals(listOf(p), ProfileSnapshot.decode(ProfileSnapshot.encode(listOf(p))))
        }
    }

    @Test fun `version two migration retains legacy byok and credentials`() {
        val old = base.copy(baseUrl = root, cloudflareByok = true, cloudflareGatewayId = "zero",
            modelId = "google/exact")
        val raw = JSONObject(ProfileSnapshot.encode(listOf(old))).put("schemaVersion", 2)
        raw.getJSONArray("connections").getJSONObject(0).remove("gatewayMode")
        var migrated = ""
        val owner = ProfilePersistence(raw.toString(), null) { migrated = it; true }
        assertNull(owner.error)
        assertEquals(GatewayMode.CF_BYOK, owner.profiles.single().effectiveGatewayMode())
        assertEquals(old.apiKeyRef, owner.profiles.single().apiKeyRef)
        assertEquals(3, JSONObject(migrated).getInt("schemaVersion"))
        var writes = 0
        ProfilePersistence(migrated, null) { writes++; true }
        assertEquals(0, writes)
    }

    @Test fun `cf qwen contract rejects unsupported effort and keeps stream sync fields equal`() {
        val p = base.copy(contractId = "cf-qwen38-v1", parameters = mapOf("effort" to "xhigh"))
        for (stream in listOf(true, false)) {
            val body = JSONObject().put("max_tokens", p.maxTokens).put("temperature", p.temperature)
            CloudflareChatTransport.applyContract(body, p, stream)
            assertEquals("xhigh", body.getString("reasoning_effort"))
            assertEquals(p.maxTokens, body.getInt("max_completion_tokens"))
            assertFalse(body.has("enable_thinking"))
            assertFalse(body.has("max_tokens"))
        }
        assertNotNull(ModelContracts.validationError(p.copy(parameters = mapOf("effort" to "high"))))
        assertEquals("low", ModelContracts.get(p.contractId).bounded(p).parameters["effort"])
    }
}
