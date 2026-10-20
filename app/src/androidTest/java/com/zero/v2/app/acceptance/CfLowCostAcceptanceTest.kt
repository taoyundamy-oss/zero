package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.services.llm.GatewayMode
import com.zero.v2.services.llm.ModelRequestHealth
import com.zero.v2.services.llm.OpenAiCompatibleClient
import com.zero.v2.services.llm.RequestContextBudget
import com.zero.v2.services.llm.RequestContextPreparation
import com.zero.v2.services.llm.StoredAttachmentReader
import com.zero.v2.services.security.CredentialStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/** Explicit paid-network opt-in. Four durable attempts, synthetic text, no saved profile writes. */
class CfLowCostAcceptanceTest {
    @Test fun remainingStreamDiagnostic() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        check(InstrumentationRegistry.getArguments().getString("cfStreamDiagnostic") == "true")
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val saved = requireNotNull(entry.profiles().get("qwen"))
        val key = requireNotNull(entry.llmClient().resolveApiKey(saved))
        val journal = File(context.filesDir, "llm-cf-final-20261011-attempts.json")
        val used = JSONObject(journal.readText()).getInt("requests")
        check(used == 3)
        val profile = saved.copy(modelId = "anthropic/claude-haiku-4.5", gatewayMode = GatewayMode.CF_GATEWAY,
            cloudflareGatewayId = "default", contractId = "generic-v1", parameters = emptyMap(), maxTokens = 256)
        val payload = JSONObject().put("model", profile.modelId).put("stream", true).put("max_tokens", 256)
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", "What is 19 plus 23? Reply with the number only.")))
        val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build()
        journal.writeText(JSONObject().put("requests", used + 1).toString())
        val request = com.zero.v2.services.llm.CloudflareChatTransport.request(profile, key)
            .header("cf-aig-max-attempts", "1").header("cf-aig-skip-cache", "true")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            File(context.filesDir, "llm-cf-final-sse-diagnostic.json").writeText(JSONObject()
                .put("httpStatus", response.code).put("contentType", response.header("content-type"))
                .put("body", response.body!!.string().replace(key, "[redacted]").take(12000)).toString(2))
        }
    }

    @Test fun chatAndSse() = runBlocking<Unit> {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        check(InstrumentationRegistry.getArguments().getString("cfLowCost") == "true")
        val repair = InstrumentationRegistry.getArguments().getString("repairSse") == "true"
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val saved = requireNotNull(entry.profiles().get("qwen"))
        check(URI(saved.baseUrl).host == "api.cloudflare.com")
        val key = requireNotNull(entry.llmClient().resolveApiKey(saved))
        val journal = File(context.filesDir, if (repair) "llm-cf-final-repair-attempts.json" else "llm-cf-final-20261011-attempts.json")
        val output = File(context.filesDir, if (repair) "llm-cf-final-repair.json" else "llm-cf-final-20261011.json")
        val cap = if (repair) 1 else 4
        check(!output.exists()) { "Evidence exists; do not reset or repeat paid acceptance" }
        val rows = JSONArray()
        val report = JSONObject().put("requestCap", cap).put("outputTokenCap", 256)
            .put("route", "CF_GATEWAY REST default; BYOK if configured, otherwise Unified Billing")
            .put("rows", rows)
        fun flush() = output.writeText(report.toString(2))
        val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val used = if (journal.exists()) JSONObject(journal.readText()).getInt("requests") else 0
                check(used < cap) { "acceptance_request_cap" }
                journal.writeText(JSONObject().put("requests", used + 1).toString())
                chain.proceed(chain.request().newBuilder().header("cf-aig-max-attempts", "1")
                    .header("cf-aig-skip-cache", "true").build())
            }.build()
        val credentials = object : CredentialStore {
            override fun getString(key: String): String = error("Explicit credential required")
            override fun getOrFallbackString(primaryKey: String, fallbackKey: String?): String =
                error("Explicit credential required")
            override fun isConfigured(key: String) = false
            override fun put(key: String, value: String): Unit = error("Read only acceptance")
            override fun delete(key: String): Unit = error("Read only acceptance")
        }
        val client = OpenAiCompatibleClient(http, credentials, entry.traces(), entry.budget(),
            RequestContextPreparation(StoredAttachmentReader(context), RequestContextBudget(), http), ModelRequestHealth())
        flush()
        val models = if (repair) listOf("anthropic/claude-haiku-4.5") else listOf("openai/gpt-6-luna", "anthropic/claude-haiku-4.5")
        for (wire in models) {
            val gpt = wire.startsWith("openai/")
            val profile = saved.copy(id = "acceptance-cf-${if (gpt) "luna" else "haiku"}",
                modelId = wire, gatewayMode = GatewayMode.CF_GATEWAY, cloudflareGatewayId = "default",
                contractId = if (gpt) "effort-v1" else "generic-v1",
                parameters = if (gpt) mapOf("effort" to "none") else emptyMap(),
                maxTokens = 256, maxOutputTokens = 256, temperature = 0f, localLlamaPreflight = false)
            for (stream in if (repair) listOf(true) else listOf(false, true)) {
                val row = JSONObject().put("model", wire).put("stream", stream).put("contract", profile.contractId)
                rows.put(row); flush()
                try {
                    check(entry.budget().preCheck("llm-cf-final") == com.zero.v2.services.agent.BudgetGuard.Verdict.OK)
                    val text = StringBuilder()
                    var final = !stream
                    if (!stream) text.append(client.chatSync(profile, "What is 6 times 7? Reply with the number only.", apiKey = key))
                    else client.chatStream(profile, listOf(ChatMessage(role = MessageRole.USER,
                        content = "What is 19 plus 23? Reply with the number only.")), null, emptyList(), key).collect {
                        it.contentDelta?.let(text::append)
                        final = final || it.isFinal
                        check(it.errorCode == null)
                    }
                    row.put("answer", text.toString().replace(key, "[redacted]").take(500)).put("final", final)
                    check(final && text.contains("42") && !text.startsWith("[错误"))
                    row.put("passed", true)
                } catch (failure: Exception) {
                    row.put("passed", false).put("error", failure.message.orEmpty().replace(key, "[redacted]")
                        .replace(saved.baseUrl, "[route]").take(500))
                }
                flush()
                if (!row.getBoolean("passed")) break
            }
        }
        val usage = client.usageTotal.value
        report.put("reportedInputTokens", usage.inputTokens).put("reportedOutputTokens", usage.outputTokens)
        flush()
        check(rows.length() == cap && (0 until rows.length()).all { rows.getJSONObject(it).getBoolean("passed") })
    }
}
