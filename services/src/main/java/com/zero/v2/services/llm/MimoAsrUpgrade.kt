package com.zero.v2.services.llm

/** Correct the legacy API identifier without replacing user tuning or credential references. */
internal object MimoAsrUpgrade {
    fun apply(profile: LlmProfile): LlmProfile {
        val host = runCatching { java.net.URI(profile.baseUrl).host }.getOrNull()
        return if (host == "api.xiaomimimo.com" && profile.kind == LlmProfile.Kind.ASR &&
            profile.modelId == "MiMo-V2.5-ASR") {
            profile.copy(modelId = "mimo-v2.5-asr")
        } else profile
    }
}
