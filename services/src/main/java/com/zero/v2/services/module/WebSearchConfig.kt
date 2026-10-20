package com.zero.v2.services.module

import com.zero.v2.core.moduleapi.ModuleConfigBase
import org.json.JSONObject

/**
 * Web 搜索模块配置 — Zero v2 Phase 2。
 *
 * apiKeyRef 指向 SecureKeyStore 中的 Key，实现 Secret/Config 分离。
 * WebSearch 默认复用 Qwen API Key（与 QwenChatModule 共用 DashScope 端点）。
 */
class WebSearchConfig : ModuleConfigBase() {

    /**
     * Key 的 SecureKeyStore 条目引用（N4.3：撤下 @ConfigField——SECRET 字段留在 AutoForm
     * 会误导用户把明文 Key 粘进掩码框直存 config，v5.8 同款教训）。Key 统一走模块详情页
     * "🔑 API Keys" 卡片（buildApiKeyFields：web_search:qwen → llm_client:qwen 回退）。
     */
    var apiKeyRef: String = "llm_client:qwen"

    /** S1.2-c 退役 ConfigField：端点/模型/Key 唯一源 = qwen-search profile（模块详情页
     * 「模型管理」编辑子页，单入口裁定）。保留 var + JSON 往返仅作一次性迁移源（profile() 搬运）。 */
    var searchModelId: String = "qwen3.5-flash"

    var endpoint: String = "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1"

    override fun toJson(): String {
        return """{"apiKeyRef":"$apiKeyRef","searchModelId":"$searchModelId","endpoint":"$endpoint"}"""
    }

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            apiKeyRef = obj.optString("apiKeyRef", "llm_client:qwen")
            searchModelId = obj.optString("searchModelId", "qwen3.5-flash")
            endpoint = obj.optString("endpoint",
                "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1")
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = WebSearchConfig()
}
