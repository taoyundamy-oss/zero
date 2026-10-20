package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/** New user-authorized diagnostic budget. Credentials remain in app memory and end-to-end TLS. */
class CfOpenAiDiagnosticTest {
    @Test fun compareRoutesAndEgress() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("cfOpenAiDiagnostic") == "true")
        val route = requireNotNull(args.getString("egress"))
        check(route in setOf("phone", "pc"))
        val confirm = args.getString("confirmNetwork") == "true"
        val phase = if (confirm) "confirm" else "diagnostic"
        val cap = if (confirm) 2 else 7
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val saved = requireNotNull(entry.profiles().get("qwen"))
        val uri = URI(saved.baseUrl)
        check(uri.host == "api.cloudflare.com")
        val account = Regex("/client/v4/accounts/([^/]+)/ai/v1/?").matchEntire(uri.path)!!.groupValues[1]
        val key = requireNotNull(entry.llmClient().resolveApiKey(saved))
        val output = File(context.filesDir, "llm-cf-openai-$phase-$route.json")
        check(!output.exists()) { "Do not overwrite or repeat this diagnostic" }
        val journal = File(context.filesDir, "llm-cf-openai-$phase-attempts.json")
        val rows = JSONArray()
        val report = JSONObject().put("egress", route).put("rows", rows).put("postCap", cap)
        fun flush() = output.writeText(report.toString(2))
        val proxy = if (route == "pc") Proxy(Proxy.Type.HTTP,
            InetSocketAddress("127.0.0.1", requireNotNull(args.getString("relayPort")).toInt())) else Proxy.NO_PROXY
        val http = OkHttpClient.Builder().proxy(proxy).callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                if (chain.request().method == "POST") {
                    val used = if (journal.exists()) JSONObject(journal.readText()).getInt("requests") else 0
                    check(used < cap) { "diagnostic_post_cap" }
                    journal.writeText(JSONObject().put("requests", used + 1).toString())
                }
                chain.proceed(chain.request())
            }.build()
        flush()
        if (confirm) http.newCall(Request.Builder().url("https://api.cloudflare.com/cdn-cgi/trace").build())
            .execute().use { response ->
                val trace = JSONObject()
                response.body!!.string().lineSequence().forEach { line ->
                    val name = line.substringBefore('=')
                    if (name in setOf("loc", "colo", "warp", "tls", "http")) trace.put(name, line.substringAfter('='))
                }
                report.put("apiHostTrace", trace).put("apiHostTraceHttp", response.code)
            }
        http.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build()).execute().use { response ->
            val trace = JSONObject()
            response.body!!.string().lineSequence().forEach { line ->
                val name = line.substringBefore('=')
                if (name in setOf("loc", "colo", "warp", "tls", "http")) trace.put(name, line.substringAfter('='))
            }
            report.put("trace", trace).put("traceHttp", response.code)
        }
        if (route == "phone" && !confirm) {
            val gatewayRoot = "https://api.cloudflare.com/client/v4/accounts/$account/ai-gateway/gateways/default"
            for (suffix in listOf("", "/provider_configs?per_page=100")) {
                http.newCall(Request.Builder().url(gatewayRoot + suffix).header("Authorization", "Bearer $key")
                    .build()).execute().use { response ->
                    val result = JSONObject(response.body!!.string()).opt("result")
                    if (suffix.isEmpty() && result is JSONObject) {
                        val selected = JSONObject()
                        result.keys().forEach { name ->
                            if (name.contains("zdr") || name in setOf("byok_only", "authentication", "rate_limiting_limit")) {
                                selected.put(name, result.opt(name))
                            }
                        }
                        report.put("gatewaySettings", selected).put("settingsHttp", response.code)
                    } else if (result is JSONArray) {
                        val configs = JSONArray()
                        for (i in 0 until result.length()) {
                            val item = result.getJSONObject(i)
                            configs.put(JSONObject().put("provider", item.optString("provider_slug"))
                                .put("alias", item.optString("alias")))
                        }
                        report.put("providerKeys", configs)
                    }
                }
            }
        }
        flush()
        val cases = if (confirm) mutableListOf("luna_chat") else mutableListOf("luna_chat", "nano_chat", "luna_responses")
        if (route == "phone" && !confirm) cases += "luna_require_byok"
        for (case in cases) {
            val responses = case == "luna_responses"
            val model = if (case == "nano_chat") "openai/gpt-4.1-nano" else "openai/gpt-6-luna"
            val prompt = "What is 6 times 7? Reply with the number only."
            val body = JSONObject().put("model", model).put("stream", false)
            if (responses) body.put("input", prompt).put("max_output_tokens", 64)
                .put("reasoning", JSONObject().put("effort", "none")).put("store", false)
            else {
                body.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                    .put("max_completion_tokens", 64)
                if (case != "nano_chat") body.put("reasoning_effort", "none")
            }
            val row = JSONObject().put("case", case).put("model", model).put("payload", body)
            rows.put(row); flush()
            try {
                val request = Request.Builder().url(saved.baseUrl.trimEnd('/') + if (responses) "/responses" else "/chat/completions")
                    .header("Authorization", "Bearer $key").header("cf-aig-gateway-id", "default")
                    .header("cf-aig-max-attempts", "1").header("cf-aig-skip-cache", "true")
                if (case == "luna_require_byok") request.header("cf-aig-no-wholesale", "true")
                val started = System.nanoTime()
                http.newCall(request.post(body.toString().toRequestBody("application/json".toMediaType())).build())
                    .execute().use { response ->
                        row.put("httpStatus", response.code).put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                            .put("cfRay", response.header("cf-ray")).put("requestId", response.header("x-request-id"))
                            .put("gatewayLogId", response.header("cf-aig-log-id"))
                            .put("body", response.body!!.string().replace(key, "[redacted]").replace(account, "[account]").take(6000))
                    }
            } catch (failure: Exception) {
                row.put("errorClass", failure.javaClass.simpleName).put("error", failure.message.orEmpty()
                    .replace(key, "[redacted]").replace(account, "[account]").take(400))
            }
            flush()
        }
    }
}
