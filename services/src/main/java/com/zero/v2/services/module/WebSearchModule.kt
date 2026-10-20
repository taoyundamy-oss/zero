package com.zero.v2.services.module

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import com.zero.v2.services.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Web 搜索模块 — Zero v2。
 *
 * 对标 v1 WebSearchModule + 新增 ContentExtractor 深加工。
 * 使用 DashScope multimodal-generation 端点进行 Web 搜索。
 *
 * v2.2: 支持 deep_extract（深度抓取网页内容）+ 降级管道。
 *
 * v2.4: ToolDefinition 迁移 — 显式声明 ActionPermission.AUTO（只读放行，替代 SAFE）。
 */
@Singleton
class WebSearchModule
    @Inject
    constructor(
        @Named("llm") private val okHttpClient: OkHttpClient,
        private val secureKeyStore: CredentialStore,

        private val traceManager: AgentTraceManager,
        private val profileStore: LlmProfileStore,
    ) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {
        override val metadata =
            ModuleMetadata(
                id = "web_search",
                displayName = "网页搜索",
                description = "搜索互联网获取最新信息、新闻、事实数据。需要实时数据时使用。",
                category = ModuleCategory.INSTRUMENT,
                launchOrder = 10,
            )

        private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override val lifecycle = _lifecycle.asStateFlow()
        override var isEnabled: Boolean = true
        override val requiredPermissions: List<String> = emptyList()

        override val config: WebSearchConfig = WebSearchConfig()

        override suspend fun onAwake() {

            _lifecycle.value = ModuleLifecycle.AWAKE
        }

        override suspend fun onStart() {
            _lifecycle.value = ModuleLifecycle.STARTED
        }

        override suspend fun onDestroy() {
            _lifecycle.value = ModuleLifecycle.DESTROYED
        }

        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

        override suspend fun checkAvailability(): Boolean = resolveApiKey() != null

        override fun markDisabled() {
            _lifecycle.value = ModuleLifecycle.DISABLED
        }

        override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
            functionRegistry.register(
                FunctionDefinition(
                    name = "web_search",
                    description = "搜索互联网获取最新信息。需要实时数据、新闻、事实核查时使用。",
                    parameters =
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "query" to mapOf("type" to "string", "description" to "搜索关键词"),
                                    "max_results" to mapOf("type" to "number", "description" to "最大返回数 1-20"),
                                ),
                            "required" to listOf("query"),
                        ),
                ),
                metadata.id,
                permission = ActionPermission.AUTO,
            ) { args ->
                val query = args["query"] as? String ?: ""
                val maxResults = (args["max_results"] as? Number)?.toInt() ?: 10
                search(query, maxResults)
            }
        }

        /**
         * 有效 profile（S1.2-c / G12）：端点/模型/Key 经 LlmProfileStore 即时生效、与其他模块
         * profile 完全隔离（独立切换互不影响）；协议保持 DashScope 原生 enable_search（SR9 例外族）。
         * 一次性迁移：ConfigField 存量用户值搬入 profile（P1-7；幂等：迁移后 profile ≠ 预置）。
         */
        private fun profile(): LlmProfile {
            val preset = LlmProfile.defaults().first { it.id == "qwen-search" }
            val stored = profileStore.byKind(LlmProfile.Kind.SEARCH).firstOrNull() ?: preset
            if (stored == preset) {
                val fresh = WebSearchConfig()
                if (config.endpoint != fresh.endpoint || config.searchModelId != fresh.searchModelId) {
                    val migrated = stored.copy(baseUrl = config.endpoint, modelId = config.searchModelId)
                    profileStore.upsert(migrated)
                    traceManager.record(
                        "fc_web_search",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "search_config_migrated: endpoint/modelId (ConfigField->profile 一次性)",
                    )
                    return migrated
                }
            }
            return stored
        }

        private fun resolveApiKey(): String? {
            val p = profile()
            return secureKeyStore.getOrFallbackString(p.apiKeyRef, p.apiKeyRefFallback)
        }

        private suspend fun search(
            query: String,
            maxResults: Int,
        ): FunctionResult =
            withContext(Dispatchers.IO) {
                if (query.isBlank()) return@withContext FunctionResult.failure("搜索关键词不能为空")
                val p = profile()
                val apiKey = secureKeyStore.getOrFallbackString(p.apiKeyRef, p.apiKeyRefFallback)
                if (apiKey.isNullOrBlank()) return@withContext FunctionResult.failure("API Key 未配置")

                val messages =
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply { put(JSONObject().apply { put("text", query) }) })
                            },
                        )
                    }

                val requestBody =
                    JSONObject().apply {
                        put("model", p.modelId)
                        put("input", JSONObject().apply { put("messages", messages) })
                        put(
                            "parameters",
                            JSONObject().apply {
                                put("enable_search", true)
                                put(
                                    "search_options",
                                    JSONObject().apply {
                                        put("search_strategy", "agent")
                                        put("enable_source", true)
                                    },
                                )
                                put("stream", true)
                                put("incremental_output", true)
                            },
                        )
                    }

                val request =
                    Request.Builder()
                        .url("${p.baseUrl}/services/aigc/multimodal-generation/generation")
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .header("X-DashScope-SSE", "enable")
                        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext FunctionResult.failure("搜索API错误(${response.code}): ${response.body?.string()?.take(200)}")
                }

                val reader = response.body?.charStream()?.buffered() ?: return@withContext FunctionResult.failure("Empty response")
                var contentText = StringBuilder()
                val allSources = mutableListOf<SearchSource>()

                reader.lineSequence()
                    .filter { it.startsWith("data:") }
                    .map { it.removePrefix("data:").trimStart() }
                    .filter { it != "[DONE]" }
                    .forEach { data ->
                        try {
                            val json = JSONObject(data)
                            val output = json.optJSONObject("output") ?: return@forEach
                            val choices = output.optJSONArray("choices")
                            val choice = choices?.optJSONObject(0)
                            val message = choice?.optJSONObject("message")

                            // 提取增量内容
                            val delta = extractDeltaText(message)
                            if (delta.isNotEmpty()) contentText.append(delta)

                            // 提取搜索来源
                            val searchInfo =
                                output.optJSONObject("search_info")
                                    ?: choice?.optJSONObject("search_info")
                                    ?: message?.optJSONObject("search_info")
                            if (searchInfo != null && allSources.isEmpty()) {
                                val searchResults = searchInfo.optJSONArray("search_results")
                                if (searchResults != null) {
                                    for (i in 0 until searchResults.length()) {
                                        val item = searchResults.getJSONObject(i)
                                        allSources.add(
                                            SearchSource(
                                                title = item.optString("title", "无标题"),
                                                url = item.optString("url", ""),
                                                snippet =
                                                    item.optString("snippet", "")
                                                        .ifBlank { item.optString("content", "").ifBlank { item.optString("title", "") } },
                                            ),
                                        )
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                val formattedText =
                    buildString {
                        appendLine("=== 网页搜索结果 ===")
                        appendLine("关键词: $query | 来源: ${allSources.size} 条")
                        appendLine()
                        allSources.take(maxResults).forEachIndexed { i, src ->
                            appendLine("[${i + 1}] ${src.title}")
                            appendLine("    链接: ${src.url}")
                            if (src.snippet.isNotBlank()) appendLine("    摘要: ${src.snippet.take(300)}")
                            appendLine()
                        }
                        if (contentText.isNotBlank()) {
                            appendLine("---")
                            appendLine(contentText.toString().take(2000))
                        }
                        appendLine("---")
                        appendLine("请基于以上搜索结果回答用户问题，并引用来源编号。")
                    }

                FunctionResult.success(formattedText)
            }

        private fun extractDeltaText(message: JSONObject?): String {
            if (message == null) return ""
            val content = message.opt("content")
            return when {
                content is JSONArray && content.length() > 0 -> {
                    (0 until content.length()).mapNotNull { i ->
                        content.optJSONObject(i)?.optString("text", "")?.takeIf { it.isNotBlank() }
                    }.joinToString("")
                }
                content is String && content != "[]" && content.isNotBlank() -> content
                else -> message.optString("reasoning_content", "")
            }
        }

        data class SearchSource(val title: String, val url: String, val snippet: String)
    }
