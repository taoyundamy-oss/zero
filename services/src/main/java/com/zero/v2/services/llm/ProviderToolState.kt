package com.zero.v2.services.llm

import com.zero.v2.core.common.ToolProviderState
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Observed OpenAI wire extension, independent of model naming and parameter dialect. */
internal object ProviderToolState {
    fun scope(profile: LlmProfile): String {
        val identity = JSONArray(listOf(profile.id, profile.connectionId, profile.baseUrl.trimEnd('/'),
            profile.modelId, profile.contractId, profile.authMode.name, profile.authHeader,
            profile.apiKeyRef, profile.apiKeyRefFallback, profile.effectiveGatewayMode().name,
            profile.cloudflareGatewayId)).toString()
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun read(call: JSONObject, scope: String?): ToolProviderState? {
        val google = call.optJSONObject("extra_content")?.optJSONObject("google")
        if (google == null || !google.has("thought_signature")) return null
        val value = google.get("thought_signature")
        require(value is String && value.isNotEmpty()) { "provider_tool_signature_invalid" }
        check(scope != null) { "provider_tool_scope_missing" }
        return ToolProviderState(scope, value)
    }

    fun replay(call: JSONObject, state: ToolProviderState?, profile: LlmProfile) {
        // Historical transcripts may be sent to a different model on a subsequent user turn.
        // A signature is never forwarded outside its original route.
        if (state == null || state.scope != scope(profile)) return
        call.put("extra_content", JSONObject().put("google",
            JSONObject().put("thought_signature", state.googleThoughtSignature)))
    }
}
