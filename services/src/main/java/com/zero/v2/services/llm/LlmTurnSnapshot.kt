package com.zero.v2.services.llm

/** Memory only; do not persist or log this object or its credential. */
internal class LlmTurnSnapshot(source: LlmProfile, resolve: (LlmProfile) -> String?) {
    val profile: LlmProfile = source.copy(
        parameters = source.parameters.toMap(), capabilities = source.capabilities.toSet())
    val credential: String = resolve(profile).orEmpty()
}
