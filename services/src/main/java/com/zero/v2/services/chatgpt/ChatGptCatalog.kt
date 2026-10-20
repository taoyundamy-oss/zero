package com.zero.v2.services.chatgpt

import com.zero.v2.services.llm.ChatGptPlanProtocol
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelTokenPrices
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ChatGptCatalog @Inject constructor(private val auth: ChatGptAuth) {
    suspend fun fetch(client: String): List<LlmProfile> = withContext(Dispatchers.IO) {
        val lease = auth.lease(client)
        val data = ChatGptHttp().models(lease.token())
        lease.checkCurrent()
        parse(client, data)
    }

    companion object {
        private const val FALLBACK_CONTEXT = 32768
        private const val DEFAULT_RESERVE = 4096
        /** Only explicit catalog capability metadata is trusted; missing metadata stays conservative. */
        internal fun parse(client: String, json: JSONObject): List<LlmProfile> {
            val models = json.getJSONArray("models")
            return (0 until models.length()).map(models::getJSONObject)
                .filter { it.optString("visibility") == "list" }.map { model ->
                    val slug = model.getString("slug").also { require(it.isNotBlank()) }
                    val context = model.optInt("context_window", FALLBACK_CONTEXT).also { require(it > 1) }
                    val digest = MessageDigest.getInstance("SHA-256").digest("$client:$slug".toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    LlmProfile("chatgpt:$digest", model.getString("display_name"), ChatGptPlanProtocol.BASE, "",
                        modelId = slug, enabled = false, connectionId = client,
                        contextWindow = context, maxTokens = minOf(DEFAULT_RESERVE, context / 2),
                        contractId = ChatGptPlanProtocol.CONTRACT,
                        capabilities = capabilities(model), tokenPrices = ModelTokenPrices(subscription = true))
                }.distinctBy { it.id }
        }

        private fun capabilities(model: JSONObject): Set<ModelCapability> = buildSet {
            add(ModelCapability.CHAT)
            if (model.optBoolean("supports_tool_calls")) add(ModelCapability.FUNCTION_CALLING)
            if (model.optBoolean("supports_reasoning")) add(ModelCapability.REASONING)
            val inputs = model.optJSONArray("input_modalities")
            if (inputs != null && (0 until inputs.length()).any { inputs.optString(it) == "image" }) {
                add(ModelCapability.IMAGE_INPUT)
                add(ModelCapability.VISION)
            }
        }
    }
}
