package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.*
import com.zero.v2.services.security.CredentialStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Explicit paid opt-in, durable global attempt cap, synthetic data and isolated conversation. */
class CacheLiveAcceptanceTest {
    @Test fun realLargeResultAndProcessContinuation() = runBlocking<Unit> {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("allowC3Live") == "true")
        val stage = args.getString("stage") ?: "seed"
        check(stage in setOf("seed", "restore"))
        val context = runner.targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val selected = provider.profiles().getEnabled(LlmProfile.Kind.CHAT)
            .filter { ModelCapability.CHAT in it.capabilities }
            .maxWithOrNull(compareBy<LlmProfile> { it.priority }.thenBy { it.contextWindow })!!
        check(selected.gatewayMode == GatewayMode.CF_GATEWAY && selected.modelId.contains("gpt"))
        val profile = selected.copy(id = "isolated-c3-cf", maxTokens = minOf(selected.maxTokens, 1536))
        val key = provider.llmClient().resolveApiKey(selected)!!
        val folder = File(context.filesDir, "c3-live-20261012").apply { mkdirs() }
        val ledgerFile = File(folder, "ledger.json")
        val rows = if (ledgerFile.exists()) JSONArray(ledgerFile.readText()) else JSONArray()
        val stageFile = File(folder, "$stage.json")
        check(!stageFile.exists()) { "Do not overwrite or rerun a paid cohort" }
        val report = JSONObject().put("stage", stage).put("pid", android.os.Process.myPid()).put("accepted", false)
            .put("model", selected.modelId).put("contract", selected.contractId)
        stageFile.writeText(report.toString(2))
        val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                check(rows.length() < 12) { "C3 cumulative HTTP cap reached" }
                val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
                val body = buffer.readUtf8()
                check(body.length <= 60000) { "C3 per-request size cap" }
                val json = JSONObject(body)
                check(json.optInt("max_output_tokens", json.optInt("max_tokens", 0)) <= 1536) {
                    "C3 recovery must not exceed announced output cap"
                }
                val row = JSONObject().put("stage", stage).put("bodyChars", body.length)
                    .put("bodyHash", digest(body)).put("status", "started")
                    .put("startElapsedMs", android.os.SystemClock.elapsedRealtime())
                rows.put(row); ledgerFile.writeText(rows.toString(2))
                try {
                    val response = chain.proceed(chain.request().newBuilder().header("cf-aig-max-attempts", "1")
                        .header("cf-aig-skip-cache", "true").build())
                    row.put("http", response.code).put("status", "headers_received")
                    ledgerFile.writeText(rows.toString(2)); response
                } catch (failure: java.io.IOException) {
                    row.put("status", "io_failure"); ledgerFile.writeText(rows.toString(2)); throw failure
                }
            }.build()
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = folder }
        val credentials = object : CredentialStore {
            override fun getString(key: String) = error("Explicit credential only")
            override fun getOrFallbackString(primaryKey: String, fallbackKey: String?) = key
            override fun isConfigured(key: String) = true
            override fun put(key: String, value: String): Unit = error("No configuration writes")
            override fun delete(key: String): Unit = error("No configuration writes")
        }
        val client = OpenAiCompatibleClient(http, credentials, provider.traces(), provider.budget(),
            RequestContextPreparation(StoredAttachmentReader(context), RequestContextBudget(RequestBudgetStore(isolated)), http,
                CacheRequestDiagnostics(CacheDiagnosticsStore(isolated))), ModelRequestHealth())
        val model = ProfileLanguageModel(profile.id, profile, client, provider.profiles())
        val selector = entry.modelSelector()
        check(selector.getAll().isEmpty())
        selector.register(model)
        val tool = FunctionDefinition("c3_audit_ledger", "只读获取合成业务台账完整数据，含更正版本；每个任务只需获取一次",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
        var executions = 0
        val data = (1..300).joinToString("\n") { n ->
            "REC-${n.toString().padStart(3, '0')}|version=1|status=pending|code=BASE-$n|amount=${n * 7}|" +
                "source=department-ledger-$n|note=initial unverified accounting entry"
        } + "\nEND"
        val original = data.replace("REC-121|",
            "REC-120|version=2|status=confirmed|code=CORRECT-7291|amount=385|supersedes=version1\nREC-121|")
        entry.toolRegistrar().register(tool, "chatbot") { executions++; FunctionResult.success(original) }
        val db = Room.databaseBuilder(context, ZeroDatabase::class.java, "c3-live-isolated.db").build()
        val repo = ConversationRepository(db.conversationDao(), db.messageDao())
        val config = entry.contextBuilder().config as ContextBuilderConfig
        val evidence = config.evidenceAutoInject
        try {
            config.evidenceAutoInject = false
            val engine = entry.chatBot()
            engine.initialize("只基于实际读取的数据回答；版本更正以最新版本为准，缺少证据时明确说明。")
            val idFile = File(folder, "conversation-id")
            val id = if (stage == "seed") repo.createConversation("isolated C3 ledger").also { idFile.writeText(it) }
                else idFile.readText()
            engine.loadHistory(id, repo.getMessages(id).mapNotNull(repo::toChatMessage))
            val prompt = if (stage == "seed")
                "请通过c3_audit_ledger取得台账，核对REC-120的最新版本状态、code和amount。预览没有完整信息时读取已有结果，不要重复获取台账。仅回答这三个字段。"
            else "请从已获取台账的原文重新核对REC-120的最新版本，读原文但不要重新执行c3_audit_ledger。仅回答状态、code和amount。"
            withTimeout(240000) { engine.sendMessage(prompt, listOf(tool)).collect { chunk ->
                if (rows.length() > 0) {
                    val row = rows.getJSONObject(rows.length() - 1)
                    chunk.tokenUsage?.let { usage -> row.put("input", usage.inputTokens).put("output", usage.outputTokens)
                        .put("hit", usage.cachedTokens).put("observedInput", usage.cacheObservedInputTokens)
                        .put("toUsageMs", android.os.SystemClock.elapsedRealtime() - row.getLong("startElapsedMs")) }
                    if (chunk.isFinal) row.put("terminal", chunk.errorCode ?: "completed")
                    ledgerFile.writeText(rows.toString(2))
                }
                engine.applyStreamChunk(chunk)
            } }
            val history = engine.chatHistory.value.messages
            repo.persistTurn(id, history, history.last { it.role == MessageRole.USER }.id)
            val answer = history.last().content.orEmpty()
            report.put("answer", answer).put("toolExecutions", executions).put("originalChars", original.length)
                .put("readerCalls", history.sumOf { m -> m.toolCalls.count { it.name == "read_tool_result" ||
                    (it.name == "invoke_tool" && it.arguments.contains("read_tool_result")) } })
            assertEquals(ChatState.IDLE, engine.chatState.value)
            assertEquals(if (stage == "seed") 1 else 0, executions)
            assertTrue(answer.contains("confirmed") && answer.contains("CORRECT-7291") && answer.contains("385"))
            assertTrue(report.getInt("readerCalls") > 0)
            report.put("accepted", true)
        } finally {
            report.put("traces", JSONArray(provider.traces().getTrace("llm_call")
                .filter { it.sourceModule == profile.id &&
                    (it.summary.startsWith("request_prefix:") || it.summary.startsWith("cache_observation:")) }
                .map { it.summary }))
            report.put("requestsCumulative", rows.length())
            stageFile.writeText(report.toString(2))
            config.evidenceAutoInject = evidence
            selector.unregister(profile.id)
            entry.toolRegistrar().unregister(tool.name)
            db.close()
        }
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
