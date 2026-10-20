package com.zero.v2.services.llm

/** One-time capability correction for the official endpoint, retaining all user tuning and key references. */
internal object DeepSeekVisionUpgrade {
    private val models = setOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp")
    fun apply(profile: LlmProfile): LlmProfile {
        val host = runCatching { java.net.URI(profile.baseUrl).host }.getOrNull()
        val old = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING, ModelCapability.LONG_CONTEXT)
        val legacyCaps = profile.capabilities == old || profile.capabilities == old + ModelCapability.REASONING
        val officialChat = host == "api.deepseek.com" && profile.kind == LlmProfile.Kind.CHAT
        return if (officialChat && profile.modelId in models && legacyCaps) {
            profile.copy(capabilities = profile.capabilities +
                setOf(ModelCapability.IMAGE_INPUT, ModelCapability.VISION))
        } else profile
    }
}
