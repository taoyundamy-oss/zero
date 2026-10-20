package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Read-only, bounded historical error investigation. Never export credentials or message content. */
class CloudflareFailureLogAcceptanceTest {
    @Test fun readOriginalQwenFailureMetadata() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is ProviderAcceptanceRunner)
        check(InstrumentationRegistry.getArguments().getString("readCfFailures") == "true")
        val context = instrumentation.targetContext
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val profile = requireNotNull(provider.profiles().get("qwen"))
        val uri = URI(profile.baseUrl)
        check(uri.host == "api.cloudflare.com")
        val account = Regex("/client/v4/accounts/([^/]+)/ai/v1/?").matchEntire(uri.path)!!.groupValues[1]
        val gateway = profile.cloudflareGatewayId.ifBlank { "default" }
        val key = requireNotNull(provider.llmClient().resolveApiKey(profile))
        val gatewaysRoot = "https://api.cloudflare.com/client/v4/accounts/$account/ai-gateway/gateways"
        val report = JSONObject().put("scope", "historical_error_metadata_only")
        val output = File(context.filesDir, "llm-u4b-cf-logs.json")
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        fun read(url: String): Pair<Int, String> = http.newCall(Request.Builder().url(url)
            .header("Authorization", "Bearer $key").build()).execute().use { it.code to it.body?.string().orEmpty() }
        try {
            val inventory = read("$gatewaysRoot?per_page=10")
            report.put("gatewayListStatus", inventory.first)
            val candidates = mutableListOf(gateway)
            if (inventory.first == 200) {
                val gateways = JSONObject(inventory.second).optJSONArray("result") ?: JSONArray()
                for (index in 0 until gateways.length()) candidates += gateways.getJSONObject(index).getString("id")
            }
            val batches = JSONArray()
            report.put("gateways", batches)
            for ((index, candidate) in candidates.distinct().take(3).withIndex()) {
                val base = "$gatewaysRoot/$candidate/logs"
                val batch = JSONObject().put("gatewayIndex", index)
                batches.put(batch)
                val url = base.toHttpUrl().newBuilder().addQueryParameter("per_page", "10")
                    .addQueryParameter("start_date", "2026-10-11T03:25:00Z")
                    .addQueryParameter("end_date", "2026-10-11T03:40:00Z")
                    .addQueryParameter("success", "false").build().toString()
                val (status, raw) = read(url)
                batch.put("listStatus", status)
                if (status != 200) {
                    batch.put("error", raw.replace(key, "[redacted]").replace(account, "[account]").take(1000))
                } else {
                    val results = JSONObject(raw).optJSONArray("result") ?: JSONArray()
                    batch.put("matchedCount", results.length())
                    val selected = JSONArray()
                    batch.put("logs", selected)
                    for (i in 0 until results.length()) {
                        val log = results.getJSONObject(i)
                        if (!log.optString("model").contains("qwen", true) || log.optInt("status_code") != 400) continue
                        if (selected.length() >= 2) break
                        val row = JSONObject()
                        listOf("id", "created_at", "model", "provider", "status_code", "tokens_in", "tokens_out")
                            .forEach { row.put(it, log.opt(it)) }
                        selected.put(row)
                        val id = log.getString("id")
                        val response = read("$base/$id/response")
                        row.put("responseReadStatus", response.first)
                        row.put("upstreamError", response.second.replace(key, "[redacted]")
                            .replace(account, "[account]").take(3000))
                        val request = read("$base/$id/request")
                        row.put("requestReadStatus", request.first)
                        if (request.first == 200) row.put("requestShape", shape(JSONObject(request.second)))
                    }
                }
            }

        } catch (failure: Exception) { report.put("errorClass", failure.javaClass.simpleName) }
        output.writeText(report.toString(2))
    }

    private fun shape(value: JSONObject): JSONObject {
        val out = JSONObject().put("keys", JSONArray(value.keys().asSequence().toList()))
        listOf("model", "stream", "max_tokens", "max_completion_tokens", "temperature", "reasoning_effort",
            "enable_thinking", "thinking_budget").forEach { if (value.has(it)) out.put(it, value.opt(it)) }
        value.optJSONArray("messages")?.let { messages ->
            out.put("messages", JSONArray((0 until messages.length()).map { index ->
                val message = messages.getJSONObject(index)
                JSONObject().put("role", message.optString("role"))
                    .put("contentType", message.opt("content")?.javaClass?.simpleName)
                    .put("contentChars", message.opt("content")?.toString()?.length)
                    .put("toolCalls", message.optJSONArray("tool_calls")?.length() ?: 0)
                    .put("toolResult", message.has("tool_call_id"))
            }))
        }
        value.optJSONArray("tools")?.let { out.put("toolCount", it.length()) }
        listOf("result", "body", "request", "extra_body").forEach { key ->
            value.optJSONObject(key)?.let { out.put(key, shape(it)) }
            (value.opt(key) as? String)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let { out.put(key, shape(it)) }
            }
        }
        return out
    }
}
