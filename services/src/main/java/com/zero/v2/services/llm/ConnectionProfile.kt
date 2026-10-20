package com.zero.v2.services.llm

enum class ConnectionAuth { NONE, BEARER, HEADER }

/** Persisted independently of model identity; secrets remain in CredentialStore. */
data class ConnectionProfile(
    val id: String,
    val baseUrl: String,
    val apiKeyRef: String,
    val apiKeyRefFallback: String? = null,
    val authMode: ConnectionAuth = ConnectionAuth.BEARER,
    val authHeader: String = "Authorization",
    val cloudflareByok: Boolean = false,
    val cloudflareGatewayId: String = "",
    val gatewayMode: GatewayMode = GatewayMode.DIRECT,
) {
    fun project(profile: LlmProfile): LlmProfile = profile.copy(
        connectionId = id, baseUrl = baseUrl, apiKeyRef = apiKeyRef,
        apiKeyRefFallback = apiKeyRefFallback, authMode = authMode, authHeader = authHeader,
        cloudflareByok = cloudflareByok, cloudflareGatewayId = cloudflareGatewayId, gatewayMode = gatewayMode,
    )

    companion object {
        fun from(profile: LlmProfile) = ConnectionProfile(
            profile.connectionId.ifBlank { "profile:${profile.id}" }, profile.baseUrl, profile.apiKeyRef,
            profile.apiKeyRefFallback, profile.authMode, profile.authHeader,
            profile.cloudflareByok, profile.cloudflareGatewayId, profile.gatewayMode,
        )
    }
}
