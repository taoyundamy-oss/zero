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
 * 视觉识别模块 — Zero v2。
 *
 * 对标 v1 VisionModule + Alife Vision。
 * 支持 Poke 快速路径（聊天中图片预处理）和 FC 调用路径（LLM 自定义 prompt）。
 *
 * v2.4: ToolDefinition 迁移 — 显式声明 ActionPermission.AUTO（只读放行，替代 SAFE）。
 *
 * v2.6 修复: API Key 从 ApiKeyProvider（System.getenv 占位 stub，Android 上恒为空）
 *       切换到 SecureKeyStore（vision_analyze:qwen → llm_client:qwen 回退链，同 WebSearchModule 模式）。
 */
@Singleton
class VisionModule
    @Inject
    constructor(
        @Named("llm") private val okHttpClient: OkHttpClient,
        private val secureKeyStore: CredentialStore,

        private val traceManager: AgentTraceManager,
        private val profileStore: LlmProfileStore,
    ) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {
        override val metadata =
            ModuleMetadata(
                id = "vision_analyze",
                displayName = "视觉识别",
                description = "分析图片内容：提取文字(OCR)、识别物体、描述场景、分析图表。",
                category = ModuleCategory.INSTRUMENT,
                launchOrder = 20,
            )

        private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override val lifecycle = _lifecycle.asStateFlow()
        override var isEnabled: Boolean = true
        override val requiredPermissions: List<String> = emptyList()

        /** v5.2: model/endpoint 从 @Volatile 硬编码迁入 ConfigField */
        override val config = VisionConfig()

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

        /**
         * 有效 profile（S1.2-c / SR9）：端点/模型/Key 经 LlmProfileStore 即时生效——协议保持
         * DashScope 原生 multimodal-generation（不强行 OpenAI 化），仅数据源从 ConfigField 换 profile。
         * 一次性迁移：ConfigField（v5.2，S1.2-c 退役）存量用户值搬入 profile（P1-7 有绑定随模块；
         * 幂等：迁移后 profile ≠ 预置，跨重启不重复——profile 持久化即标记）。
         */
        private fun profile(): LlmProfile {
            val preset = LlmProfile.defaults().first { it.id == "qwen-vl" }
            val stored = profileStore.byKind(LlmProfile.Kind.VISION).firstOrNull() ?: preset
            if (stored == preset) {
                val fresh = VisionConfig()
                if (config.endpoint != fresh.endpoint || config.visionModelId != fresh.visionModelId) {
                    val migrated = stored.copy(baseUrl = config.endpoint, modelId = config.visionModelId)
                    profileStore.upsert(migrated)
                    traceManager.record(
                        "fc_vision_analyze",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "vision_config_migrated: endpoint/modelId (ConfigField->profile 一次性)",
                    )
                    return migrated
                }
            }
            return stored
        }

        /** Key 按 profile ref 链解析（预置已对齐模块现行链 vision_analyze:qwen → llm_client:qwen，零迁移） */
        private fun resolveApiKey(): String? {
            val p = profile()
            return secureKeyStore.getOrFallbackString(p.apiKeyRef, p.apiKeyRefFallback)
        }

        override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
            functionRegistry.register(
                FunctionDefinition(
                    name = "vision_analyze",
                    description = "分析图片内容。提取文字、识别物体、描述场景、分析图表。",
                    parameters =
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "image_data" to mapOf("type" to "string", "description" to "Base64编码的图片(data:image/...;base64,...)"),
                                    "question" to mapOf("type" to "string", "description" to "分析指令，如'提取所有文字保持段落格式'"),
                                ),
                            "required" to listOf("image_data"),
                        ),
                ),
                metadata.id,
                permission = ActionPermission.AUTO,
            ) { args ->
                analyze(
                    args["image_data"] as? String ?: "",
                    args["question"] as? String ?: "简洁描述图片内容",
                )
            }
        }

        /** Poke 快速路径 — 聊天中图片预处理（非流式，零延迟） */
        suspend fun analyzePoke(
            imageData: String,
            question: String,
        ): FunctionResult = analyze(imageData, question)

        private suspend fun analyze(
            imageData: String,
            question: String,
        ): FunctionResult =
            withContext(Dispatchers.IO) {
                if (imageData.isBlank()) {
                    traceManager.record(
                        "fc_vision_analyze",
                        NodeType.ERROR,
                        metadata.id,
                        "vision_analyze_failed: 图片数据为空",
                    )
                    return@withContext FunctionResult.failure("图片数据不能为空")
                }
                val p = profile()
                val apiKey = secureKeyStore.getOrFallbackString(p.apiKeyRef, p.apiKeyRefFallback)
                if (apiKey.isNullOrBlank()) {
                    traceManager.record(
                        "fc_vision_analyze",
                        NodeType.ERROR,
                        metadata.id,
                        "vision_analyze_failed: API Key 未配置 (${p.apiKeyRef} / ${p.apiKeyRefFallback})",
                    )
                    return@withContext FunctionResult.failure("API Key 未配置。请在模块管理 → 视觉识别 → 模型管理 中配置 API Key。")
                }

                val contentArray =
                    JSONArray().apply {
                        put(JSONObject().apply { put("image", imageData) })
                        put(JSONObject().apply { put("text", question) })
                    }

                val requestBody =
                    JSONObject().apply {
                        put("model", p.modelId)
                        put(
                            "input",
                            JSONObject().apply {
                                put(
                                    "messages",
                                    JSONArray().apply {
                                        put(
                                            JSONObject().apply {
                                                put("role", "user")
                                                put("content", contentArray)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }

                val request =
                    Request.Builder()
                        .url("${p.baseUrl}/services/aigc/multimodal-generation/generation")
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    traceManager.record(
                        "fc_vision_analyze",
                        NodeType.ERROR,
                        metadata.id,
                        "vision_analyze_failed: Vision API error (${response.code}): ${responseBody.take(120)}",
                    )
                    return@withContext FunctionResult.failure("Vision API error (${response.code}): ${responseBody.take(200)}")
                }

                val json = JSONObject(responseBody)
                val output = json.optJSONObject("output")
                val choices = output?.optJSONArray("choices")
                val message = choices?.optJSONObject(0)?.optJSONObject("message")
                val contentObj = message?.optJSONArray("content")

                val contentText =
                    if (contentObj != null) {
                        (0 until contentObj.length()).mapNotNull { i ->
                            contentObj.optJSONObject(i)?.optString("text", "")?.takeIf { it.isNotBlank() }
                        }.joinToString("\n")
                    } else {
                        message?.optString("content", "") ?: ""
                    }

                if (contentText.isBlank()) {
                    traceManager.record(
                        "fc_vision_analyze",
                        NodeType.ERROR,
                        metadata.id,
                        "vision_analyze_empty: 响应无文本内容 body=${responseBody.take(120)}",
                    )
                } else {
                    traceManager.record(
                        "fc_vision_analyze",
                        NodeType.TOOL_CALL_RESULT,
                        metadata.id,
                        "vision_analyze_ok: len=${contentText.length}",
                    )
                }

                if (contentText.isBlank()) FunctionResult.failure("vision_response_empty")
                else FunctionResult.success(contentText)
            }
    }

/**
 * 视觉模块配置 — v5.2（model/endpoint 用户化）；S1.2-c：两字段退役 ConfigField——
 * 端点/模型/Key 唯一源 = qwen-vl profile（模块详情页「模型管理」编辑子页，单入口裁定）。
 * 保留 var + JSON 往返仅作一次性迁移源（profile() 搬运 config → profile，见 VisionModule）。
 */
class VisionConfig : ModuleConfigBase() {
    var visionModelId: String = "qwen3-vl-flash"

    var endpoint: String = "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1"

    override fun toJson(): String {
        return """{"visionModelId":"$visionModelId","endpoint":"$endpoint"}"""
    }

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            visionModelId = obj.optString("visionModelId", "qwen3-vl-flash")
            endpoint =
                obj.optString(
                    "endpoint",
                    "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1",
                )
        } catch (_: Exception) {
        }
    }

    override fun defaultConfig(): ModuleConfigBase = VisionConfig()
}
