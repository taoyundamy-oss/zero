package com.zero.v2.services.chatgpt

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject

/** Dedicated transport; no inherited proxy credentials, retries, redirects or body logging. */
internal object ChatGptInferenceHttp {
    private const val CONNECT_SECONDS = 20L
    private const val READ_SECONDS = 120L
    private const val CALL_SECONDS = 180L
    private const val ERROR_BYTES = 16384L
    val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_SECONDS, TimeUnit.SECONDS).callTimeout(CALL_SECONDS, TimeUnit.SECONDS).build()

    fun failure(response: Response): IOException {
        val error = runCatching { JSONObject(response.peekBody(ERROR_BYTES).string()) }.getOrNull()
            ?.optJSONObject("error")
        val code = error?.optString("code").orEmpty().takeIf { it in codes } ?: "unknown"
        val shape = when {
            error != null -> "error"
            runCatching { JSONObject(response.peekBody(ERROR_BYTES).string()).has("detail") }
                .getOrDefault(false) -> "detail"
            else -> "other"
        }
        val request = response.header("x-request-id").orEmpty().takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,128}")) }
        return IOException("ChatGPT HTTP ${response.code}: $code shape=$shape request=${request ?: "unknown"}")
    }

    val codes = setOf("subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable",
        "subscription_sharing_user_not_eligible",
        "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported",
        "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized",
        "chatpass_v2_invalid_authorization_context", "subscription_sharing_user_unavailable",
        "rate_limit_exceeded", "server_error")
}
