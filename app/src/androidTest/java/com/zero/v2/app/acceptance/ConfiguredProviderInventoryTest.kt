package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.URI

/** Read-only opt-in inventory; exports no credentials, private paths or messages. */
class ConfiguredProviderInventoryTest {
    @Test fun configuredProfiles() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        check(InstrumentationRegistry.getArguments().getString("configuredInventory") == "true")
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val rows = JSONArray()
        entry.profiles().profiles.value.forEach { profile ->
            rows.put(JSONObject().put("id", profile.id).put("kind", profile.kind.name)
                .put("enabled", profile.enabled).put("host", URI(profile.baseUrl).host)
                .put("model", profile.modelId).put("contract", profile.contractId)
                .put("parameters", JSONObject(profile.parameters)).put("maxTokens", profile.maxTokens)
                .put("contextWindow", profile.contextWindow).put("auth", profile.authMode.name)
                .put("localPreflight", profile.localLlamaPreflight)
                .put("credentialPresent", !entry.llmClient().resolveApiKey(profile).isNullOrBlank()))
        }
        File(context.filesDir, "llm-u6b-configured-inventory.json").writeText(rows.toString(2))
    }

    @Test fun existingProviderCatalogs() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        check(InstrumentationRegistry.getArguments().getString("configuredCatalogs") == "true")
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val cfKey = entry.profiles().get("qwen")?.let(entry.llmClient()::resolveApiKey)
        val rows = JSONArray()
        val output = File(context.filesDir, "llm-u6b-provider-catalogs.json")
        val http = okhttp3.OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        for (id in listOf("qwen-embedding", "mimo-tts")) {
            val profile = requireNotNull(entry.profiles().get(id))
            val key = entry.llmClient().resolveApiKey(profile)
            val row = JSONObject().put("profile", id).put("host", URI(profile.baseUrl).host)
            rows.put(row)
            try {
                if (key.isNullOrBlank() || key == cfKey) {
                    row.put("skipped", "missing_or_matches_current_cf_credential")
                    continue
                }
                val request = com.zero.v2.services.llm.CloudflareChatTransport.request(profile, key)
                    .url("${profile.baseUrl.trimEnd('/')}/models").get().build()
                http.newCall(request).execute().use { response ->
                    row.put("status", response.code)
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val models = JSONObject(body).optJSONArray("data") ?: JSONArray()
                        row.put("models", JSONArray((0 until minOf(models.length(), 100)).map { index ->
                            models.getJSONObject(index).optString("id")
                        }))
                    } else row.put("error", body.replace(key, "[redacted]").take(500))
                }
            } catch (failure: Exception) { row.put("errorClass", failure.javaClass.simpleName) }
            finally { output.writeText(rows.toString(2)) }
        }
    }

}
