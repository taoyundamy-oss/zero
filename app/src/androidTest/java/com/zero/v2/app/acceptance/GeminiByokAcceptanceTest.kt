package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Explicit opt-in, read-only BYOK inventory. No keys or secret previews are exported. */
class GeminiByokAcceptanceTest {
    @Test fun inventory() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        check(InstrumentationRegistry.getArguments().getString("geminiByok") == "true")
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val saved = requireNotNull(entry.profiles().get("qwen"))
        val uri = URI(saved.baseUrl)
        check(uri.host == "api.cloudflare.com")
        val account = Regex("/client/v4/accounts/([^/]+)/ai/v1/?").matchEntire(uri.path)!!.groupValues[1]
        val key = requireNotNull(entry.llmClient().resolveApiKey(saved))
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val root = "https://api.cloudflare.com/client/v4/accounts/$account/ai-gateway/gateways"
        fun read(url: String): JSONObject = http.newCall(Request.Builder().url(url)
            .header("Authorization", "Bearer $key").build()).execute().use {
            check(it.isSuccessful) { "inventory_http_${it.code}" }
            JSONObject(it.body!!.string())
        }
        val report = JSONObject()
        val output = File(context.filesDir, "llm-u6b-inventory.json")
        try {
            val gateways = read("$root?per_page=10").getJSONArray("result")
            val rows = JSONArray()
            report.put("gateways", rows)
            for (i in 0 until minOf(gateways.length(), 3)) {
                val gateway = gateways.getJSONObject(i).getString("id")
                val configs = read("$root/$gateway/provider_configs?per_page=100").getJSONArray("result")
                val keys = JSONArray()
                rows.put(JSONObject().put("gateway", gateway).put("providers", keys))
                for (j in 0 until configs.length()) {
                    val config = configs.getJSONObject(j)
                    keys.put(JSONObject().put("provider", config.optString("provider_slug"))
                        .put("alias", config.optString("alias")).put("default", config.optBoolean("default_config")))
                }
            }
            report.put("passed", true)
        } finally { output.writeText(report.toString(2)) }
    }
}
