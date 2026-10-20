package com.zero.v2.services.llm

import com.zero.v2.core.common.TokenUsage
import java.io.IOException
import org.json.JSONObject

/** Only allowlisted classifications and numeric usage escape the provider payload. */
internal class ResponsesFailure(response: JSONObject?, event: JSONObject? = null) :
    IOException("responses_" + classification(response, event)) {
    val outputBudgetExhausted = classification(response, event) == "max_output_tokens"
    val usage: TokenUsage? = response?.optJSONObject("usage")?.let {
        ProviderUsage.read(it, responses = true)
    }

    companion object {
        private fun classification(response: JSONObject?, event: JSONObject?): String {
            val reason = response?.optJSONObject("incomplete_details")?.optString("reason")
            if (reason in setOf("max_output_tokens", "content_filter")) return requireNotNull(reason)
            val code = response?.optJSONObject("error")?.optString("code")
                ?: event?.optJSONObject("error")?.optString("code") ?: event?.optString("code")
            return if (code in setOf("server_error", "rate_limit_exceeded", "invalid_prompt", "invalid_response") ||
                code in com.zero.v2.services.chatgpt.ChatGptInferenceHttp.codes) requireNotNull(code)
            else when (response?.optString("status") ?: event?.optString("type")) {
                "incomplete", "response.incomplete" -> "incomplete_unknown"
                else -> "failed_unknown"
            }
        }
    }
}
