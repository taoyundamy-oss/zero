package com.zero.v2.services.llm

import java.net.URI

/** Only used while importing an old record without contractId; never used for live routing. */
internal object LegacyModelContract {
    fun migrate(profile: LlmProfile): LlmProfile {
        if (profile.kind != LlmProfile.Kind.CHAT || profile.cloudflareByok) return profile
        val host = runCatching { URI(profile.baseUrl).host }.getOrNull()
        return when {
            profile.id == "deepseek" && host == "api.deepseek.com" -> profile.copy(
                contractId = "deepseek-legacy-v1",
                parameters = thinkingParameters(profile, "effort", profile.reasoningEffort),
            )
            profile.id == "qwen" && host in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com") ->
                profile.copy(
                contractId = "dashscope-v1",
                parameters = thinkingParameters(profile, "budget", profile.thinkingBudget.toString()),
            )
            else -> profile
        }
    }

    private fun thinkingParameters(profile: LlmProfile, key: String, value: String): Map<String, String> =
        mapOf("thinking" to profile.enableThinking.toString()) +
            if (profile.enableThinking) mapOf(key to value) else emptyMap()
}
