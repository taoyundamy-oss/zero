package com.zero.v2.services.llm

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Uses only the library test application's disposable preferences, never com.zero.v2. */
class ProfileMigrationDeviceTest {
    @Test fun versionTwoGatewayMigratesWithAndroidJsonAndRoutesWorkers() {
        val model = LlmProfile("workers", "Workers", "https://api.cloudflare.com/client/v4/accounts/a/ai/v1", "ref",
            modelId = "@cf/qwen/qwen3.8-27b", gatewayMode = GatewayMode.CF_WORKERS,
            cloudflareGatewayId = "zero", contractId = "cf-qwen38-v1", parameters = mapOf("effort" to "low"))
        val raw = org.json.JSONObject(ProfileSnapshot.encode(listOf(model))).put("schemaVersion", 2)
        var migrated = ""
        val owner = ProfilePersistence(raw.toString(), null) { migrated = it; true }
        assertNull(owner.error)
        assertEquals(3, org.json.JSONObject(migrated).getInt("schemaVersion"))
        val restored = owner.profiles.single()
        val request = CloudflareChatTransport.request(restored, "isolated-token").build()
        assertEquals("zero", request.header("cf-aig-gateway-id"))
        val body = org.json.JSONObject()
        CloudflareChatTransport.applyContract(body, restored, true)
        assertEquals("low", body.getString("reasoning_effort"))
        assertEquals(model.maxTokens, body.getInt("max_completion_tokens"))
    }
    @Test fun migrationAndReopenPreserveLegacyAndCredentialsReferences() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "llm_migration_${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val model = LlmProfile("isolated", "Isolated", "https://example.test/v1", "reference",
                modelId = "personal-model", apiKeyRefFallback = "fallback")
            val record = LlmProfileJson.toJson(model).also { it.remove("contractId"); it.remove("parameters") }
            val legacy = JSONArray().put(record).toString()
            check(prefs.edit().putString("profiles_json", legacy).commit())
            val migrated = ProfilePersistence(null, prefs.getString("profiles_json", null)) {
                prefs.edit().putString("snapshot_json", it).commit()
            }
            assertNull(migrated.error)
            assertEquals(legacy, prefs.getString("profiles_json", null))
            val reopened = ProfilePersistence(prefs.getString("snapshot_json", null), legacy) {
                error("reopening must not write")
            }
            assertEquals(migrated.profiles, reopened.profiles)
            assertEquals(model, reopened.profiles.single().copy(connectionId = ""))
            val broken = ProfilePersistence("{\"schemaVersion\":999}", legacy) {
                error("unknown version must not write")
            }
            assertNotNull(broken.error)
            assertEquals(legacy, prefs.getString("profiles_json", null))
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
    @Test fun sharedConnectionAndContractUseAndroidJsonImplementation() {
        val model = LlmProfile("one", "One", "https://example.test/v1", "ref",
            connectionId = "shared", contractId = "dashscope-v1",
            parameters = mapOf("thinking" to "false"))
        val snapshot = ProfileSnapshot.encode(listOf(model, model.copy(id = "two")))
        assertEquals(2, ProfileSnapshot.decode(snapshot).size)
        val body = org.json.JSONObject()
        ModelContracts.get(model.contractId).apply(body, model, true)
        assertEquals(false, body.getBoolean("enable_thinking"))
        assertEquals(false, body.has("extra_body"))
        val local = model.copy(authMode = ConnectionAuth.NONE)
        assertNull(CloudflareChatTransport.request(local, null).build().header("Authorization"))
    }
}
