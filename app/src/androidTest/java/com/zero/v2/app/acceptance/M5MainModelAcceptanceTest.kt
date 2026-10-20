package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.core.data.entity.*
import com.zero.v2.services.agent.BudgetGuard
import com.zero.v2.services.llm.*
import com.zero.v2.services.security.CredentialStore
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.*
import org.junit.Test

/** Explicitly approved real main-model smoke, isolated Room. Never a historic-memory sweep. */
class M5MainModelAcceptanceTest {
    @Test fun boundedMainModelMemoryLoop() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is ProviderAcceptanceRunner)
        val args = InstrumentationRegistry.getArguments()
        val review = args.getString("m5Cloud") == "approved-review-18"
        check(review || args.getString("m5Cloud") == "approved-24") { "explicit_cloud_approval_required" }
        val selected = args.getString("m5Group")
        if (selected != null) check(selected in (if (review) M5CallBudget.REVIEW_GROUP_CAPS else M5CallBudget.GROUP_CAPS)) { "unknown_group" }
        val continuation = args.getString("m5Continuation")
        check((selected == null) == (continuation == null)) { "group_and_approval_required_together" }
        if (review) check(selected != null && continuation == M5CallBudget.REVIEW_APPROVAL) { "review_group_and_approval_required" }
        if (continuation != null) check(continuation == (if (review) M5CallBudget.REVIEW_APPROVAL else "m5-followup-20261018-1")) { "unknown_continuation" }
        val phaseId = when (selected) {
            "review-kinds" -> "other-kinds"
            "review-conflict" -> "conflict"
            "review-task" -> "task-format"
            else -> selected
        }
        val context = inst.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val saved = requireNotNull(entry.profiles().get("deepseek"))
        check(saved.enabled && saved.modelId == "deepseek-v4-flash" && saved.contractId == "deepseek-legacy-v1")
        check(java.net.URI(saved.baseUrl).host == "api.deepseek.com" && saved.maxTokens == 4096)
        check(saved.gatewayMode == GatewayMode.DIRECT && saved.authMode == ConnectionAuth.BEARER)
        val key = requireNotNull(entry.llmClient().resolveApiKey(saved)) // In memory only; never reported.
        val output = File(context.cacheDir, if (selected == null) "m5-main-v1.json" else if (review) "m5-review-$selected.json" else "m5-followup-$selected.json")
        check(!output.exists()) { "do_not_overwrite_cloud_evidence" }
        val journal = File(context.noBackupFilesDir, "m5-main-v1-budget.properties")
        if (selected == null) check(!journal.exists()) { "already_attempted_new_approval_required" }
        else check(journal.exists()) { "original_budget_required_do_not_recreate" }
        val budget = M5CallBudget(journal)
        if (continuation != null) {
            if (review) budget.approveReview(continuation) else budget.continueApproved(continuation, 25 * 60 * 1000L)
            budget.beginGroup(requireNotNull(selected))
        }
        val f = ChronicleAgentFixture(context, entry.mediaAnalysis(), liveMemoryContext = true)
        val report = JSONObject().put("profile", saved.id).put("model", saved.modelId)
            .put("contract", saved.contractId).put("parameters", JSONObject(saved.parameters))
            .put("maxTokens", saved.maxTokens).put("requests", JSONArray()).put("phases", JSONArray())
            .put("testData", "synthetic_only").put("independentGroup", selected).put("seededPreconditions", phaseId in setOf("update-complete", "withdraw", "conflict", "fresh-context")).put("moneyCost", JSONObject.NULL)
        val evidence = M5EvidenceWriter(output, redact = { it.replace(key, "[redacted]") })
        fun flush(force: Boolean = true) { report.put("budget", JSONObject(budget.snapshot())); evidence.write(report, force) }
        val http = OkHttpClient.Builder().callTimeout(75, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "api.deepseek.com" && request.url.encodedPath.endsWith("/chat/completions"))
                check(request.method == "POST")
                val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
                val bytes = buffer.size
                val body = JSONObject(buffer.readUtf8())
                check(body.getString("model") == saved.modelId && body.getBoolean("stream"))
                check(body.getInt("max_tokens") == saved.maxTokens)
                budget.reserve(bytes, body.getInt("max_tokens"))
                flush()
                chain.proceed(request)
            }.build()
        val credentials = object : CredentialStore {
            override fun getString(key: String): String = error("explicit_credential_only")
            override fun getOrFallbackString(primaryKey: String, fallbackKey: String?): String = error("explicit_credential_only")
            override fun isConfigured(key: String) = false
            override fun put(key: String, value: String): Unit = error("no_saved_credential_writes")
            override fun delete(key: String): Unit = error("no_saved_credential_writes")
        }
        val preparation = RequestContextPreparation(StoredAttachmentReader(f.scoped), RequestContextBudget(), http)
        val client = OpenAiCompatibleClient(http, credentials, f.trace, BudgetGuard(f.scoped, f.configs, f.trace),
            preparation, ModelRequestHealth())
        var auxiliary = 0
        val model = object : ILanguageModel {
            override val profileId = saved.id
            override val modelId = saved.modelId
            override val displayName = saved.displayName
            override val contextWindow = saved.contextWindow
            override val outputReserve = saved.maxTokens
            override val capabilities = saved.capabilities
            override val keyConfigured = true
            override val priority = saved.priority
            override val enabled = true
            override suspend fun boundedCompletion(userMessage: String, systemPrompt: String): String? {
                auxiliary++; error("unexpected_auxiliary_model")
            }
            override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("unexpected_sync")
            override fun estimateInputTokens(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>) =
                preparation.estimate(saved, messages, systemPrompt, functions, key)
            override fun noteContextEvent(messages: List<ChatMessage>, event: String) = preparation.noteEvent(saved, key, messages, event)
            override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
                val row = JSONObject().put("messages", json(messages)).put("system", systemPrompt)
                    .put("prefixVersion", prefixVersion).put("functions", json(functions)).put("chunks", JSONArray())
                report.getJSONArray("requests").put(row); flush()
                val started = System.nanoTime()
                try {
                    withTimeout(75_000) { // Bound the whole stream, not just OkHttp socket lifetime.
                    client.chatStream(saved, messages, systemPrompt, functions, key, prefixVersion).collect {
                        row.getJSONArray("chunks").put(json(it)); flush(force = false)
                        check(it.errorCode == null) { "provider_or_budget_failure" }
                        check(it.finishReason != "length") { "output_limit_not_semantic_failure" }
                        emit(it)
                    }
                    }
                } finally { row.put("elapsedMs", (System.nanoTime() - started) / 1_000_000); flush() }
            }
        }
        suspend fun phase(id: String, prompt: String, verify: suspend () -> Unit = {}) {
            if (phaseId != null && phaseId != id) return
            val row = JSONObject().put("id", id); report.getJSONArray("phases").put(row); flush()
            try {
                withTimeout(if (review) 420000L else if (selected == null) 180000L else 240000L) { f.bot.sendMessage((if (review) "仅在隔离合成数据库的默认personal范围内维护和核对。" else "") + prompt, f.registry.getAllDefinitions()).collect(f.bot::applyStreamChunk) }
                withTimeout(10000) { while (f.db.memoryDerivationDao().pending(1).isNotEmpty()) delay(20) }
                row.put("messages", json(f.bot.chatHistory.value.messages))
                row.put("memories", json(f.db.memoryDao().observeAll().first()))
                verify(); row.put("engineeringPassed", true)
            } catch (e: Exception) { row.put("failureClass", e.javaClass.simpleName); throw e }
            finally { row.put("derivations", json(f.db.memoryDerivationDao().all())); flush() }
        }
        try { withTimeout(budget.remainingWindowMs()) {
            flush()
            // HTTP caps are enforced by the interceptor. Do not additionally reduce the Agent's
            // tool rounds and force a tool-less final answer before it can finish saving.
            (f.bot.config as com.zero.v2.framework.chat.ChatBotModuleConfig).maxFcRounds = 6
            f.start(model, "你是Zero个人助理。当前会话只处理虚构验收资料；用可用工具完成请求，依据来源与当前记录回答，不编造。")
            if (selected != null) {
                M5RemainingSeeds.prepare(f, requireNotNull(phaseId))
                report.put("preconditions", json(f.db.memoryDao().observeAll().first())); flush()
            }
            phase("core", "以下是虚构验收资料，不是用户真实生活信息。请记住并维护：以后盘点报告先列结论，再列清单；顾遥是我虚构项目的联络人，未经确认不要代她承诺；验收蓝盒备份任务正在进行，下一步核对校验码，验收条件是两份校验码一致。") {
                val types = f.db.memoryDerivationDao().all().map { it.type }.toSet()
                check(types.containsAll(setOf(MemoryType.DIRECTIVE, MemoryType.RELATIONSHIP, MemoryType.TASK))) { "core_types_missing" }
            }
            phase("other-kinds", "继续补充虚构资料，请分别记住：我的验收档案放在北侧二号柜；验收茶歇我长期偏好温豆浆；2026年10月17日我参加了虚构纸鸢工作坊并完成登记。这些资料都只用于本次验收。") {
                check(f.db.memoryDerivationDao().all().map { it.type }.toSet().containsAll(
                    setOf(MemoryType.FACT, MemoryType.PREFERENCE, MemoryType.EVENT))) { "other_types_missing" }
                if (review) check(f.db.memoryDerivationDao().all().all { it.scope == "personal" }) { "review_scope_changed" }
            }
            phase("update-complete", "更新刚才的虚构记忆：验收档案位置更正为西侧四号柜，北侧二号柜已经过时；蓝盒备份刚完成核对，两份校验码一致，任务已完成，无需后续动作。请维护原有条目并保留历史。") {
                val active = f.db.memoryDao().observeAll().first().filter { it.status == MemoryStatus.ACTIVE }
                check(active.any { it.type == MemoryType.FACT && "西侧四号柜" in it.content && it.supersedesId != null })
                check(active.any { it.type == MemoryType.TASK && TaskMemory.decode(it.content)?.status == "DONE" && it.supersedesId != null })
            }
            phase("withdraw", "撤回虚构验收茶歇偏好：以后不再采用温豆浆偏好，请停止使用这条记忆，保留来源和历史，不做永久删除。") {
                check(f.db.memoryDao().observeAll().first().none { it.status == MemoryStatus.ACTIVE && it.type == MemoryType.PREFERENCE && "温豆浆" in it.content })
                val archived = f.db.memoryDao().observeAll().first().filter { it.status == MemoryStatus.ARCHIVED && it.type == MemoryType.PREFERENCE }
                check(archived.isNotEmpty())
                check(archived.all { !f.db.memoryDao().wasDeleted(it.id) })
                check(f.db.chronicleSourceDao().chronicle(M5RemainingSeeds.preferenceSourceId) != null || selected == null)
            }
            val conflictIds = mutableListOf<String>()
            val conflictSourceIds = mutableListOf<String>()
            if (phaseId == null || phaseId == "conflict") for ((id, text) in listOf("a" to "虚构发件箱只能用橙色箱。", "b" to "虚构发件箱只能用蓝色箱。")) {
                val source = ChronicleSourceEntity.message(MessageEntity("m5-conflict-$id", "m5-old-synthetic", "user", text,
                    source = "CHAT_WINDOW", createdAt = 100), 200)!!
                f.db.chronicleSourceDao().retainSource(source)
                val d = MemoryDerivationEntity.create(source.id, text, text, MemoryType.DIRECTIVE, "EXPLICIT", "personal", "虚构发件箱/颜色", .8f, now = 300)
                f.store.submit(d); f.store.drain(); conflictIds += d.memoryId
                conflictSourceIds += source.id
            }
            if (phaseId == "conflict") { report.put("preconditions", json(f.db.memoryDao().observeAll().first())); flush() }
            phase("conflict", "虚构发件箱留有两份相互矛盾的旧要求，我还没有决定哪个有效。请查明并告诉我现在能否确定箱子颜色，不要替我挑选或改写旧要求。") {
                check(f.store.current("personal", "虚构发件箱/颜色", System.currentTimeMillis()).state == "CONFLICT")
                check(conflictIds.all { f.db.memoryDao().getById(it)?.status == MemoryStatus.ACTIVE })
                val readResults = f.bot.chatHistory.value.messages.filter {
                    it.role == MessageRole.TOOL && it.toolCallName in setOf("memory_recall", "memory_sources")
                }.joinToString("\n") { it.content.orEmpty() }
                check(conflictSourceIds.all { it in readResults }) { "conflict_sources_not_actually_read" }
            }
            f.bot.loadHistory("m5-fresh-verification", emptyList())
            phase("fresh-context", "只根据当前虚构记忆和原始来源核对：盘点报告格式、项目联络人、档案柜位置、蓝盒备份是否还待做、茶歇是否仍有有效偏好、10月17日的活动，以及我住在哪座城市。没有依据的请明说，不要重新保存已撤回资料。") {
                val current = f.memory.contextMemories(emptyList())
                check(current.any { it.type == "DIRECTIVE" } && current.any { it.type == "RELATIONSHIP" })
                check(current.none { it.type == "TASK" && TaskMemory.decode(it.content.substringAfter('\n'))?.status == "DONE" })
                check(f.db.memoryDao().observeAll().first().none { it.status == MemoryStatus.ACTIVE && it.type == MemoryType.PREFERENCE && "温豆浆" in it.content })
            }
            if (phaseId == "task-format") phase("task-format", "以下是虚构验收资料，请记住并维护：登记墨盒清单的任务正在进行；下一步核对每盒编号，验收条件是清单与实物编号逐项一致。") {
                val tasks = f.db.memoryDao().observeAll().first().filter { it.status == MemoryStatus.ACTIVE && it.type == MemoryType.TASK }
                check(tasks.isNotEmpty()) { "task_not_saved" }
                for (memory in tasks) {
                    val task = checkNotNull(TaskMemory.decode(memory.content))
                    check(task.status == "ACTIVE" && !task.text.trimStart().startsWith("[zero-task:")) { "task_protocol_nested" }
                    check(task.next.isNotBlank() && task.acceptance.isNotBlank())
                }
            }
            check(auxiliary == 0)
            report.put("engineeringPassed", true).put("semanticReview", "pending_human_review_of_synthetic_transcript")
        } } finally { withContext(NonCancellable) {
            report.put("auxiliaryCalls", auxiliary).put("usage", json(client.usageTotal.value)); flush()
            f.close()
            context.deleteSharedPreferences(f.root.name + "zero_budget_guard")
            http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        } }
    }
    private fun json(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Number, is Boolean -> value
        is Enum<*> -> value.name
        is Map<*, *> -> JSONObject().also { out -> value.forEach { (k, v) -> out.put(k.toString(), json(v)) } }
        is Iterable<*> -> JSONArray().also { out -> value.forEach { out.put(json(it)) } }
        else -> JSONObject().also { out -> value.javaClass.declaredFields.filter {
            !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.forEach { it.isAccessible = true; out.put(it.name, json(it.get(value))) } }
    }
}
