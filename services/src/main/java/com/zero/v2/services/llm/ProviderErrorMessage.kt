package com.zero.v2.services.llm

import org.json.JSONArray
import org.json.JSONObject

/** Extract only structured error messages, including Gateway's indexed upstream responses. */
internal object ProviderErrorMessage {
    fun redacted(raw: String, key: String?): String = parse(raw).let {
        if (key.isNullOrBlank()) it else it.replace(key, "[redacted]")
    }

    /** Inspect status before decoding the success object; gateways may return an error array. */
    fun chatResponse(response: okhttp3.Response, key: String?): JSONObject = response.use {
        val raw = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw java.io.IOException("HTTP ${it.code}: ${redacted(raw, key)}")
        runCatching { JSONObject(raw) }.getOrElse {
            throw java.io.IOException("provider_invalid_response: ${redacted(raw, key)}")
        }
    }

    const val MAX_TEXT_LENGTH = 1000
    private const val MAX_DEPTH = 5
    fun parse(raw: String): String =
        runCatching { extract(org.json.JSONTokener(raw).nextValue(), 0) }.getOrNull()?.take(MAX_TEXT_LENGTH)
            ?: "请求被服务端拒绝"

    private fun extract(value: Any?, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        return when (value) {
            is JSONObject -> {
                val message = value.opt("message") as? String
                message?.takeIf { it.isNotBlank() }
                    ?: extract(value.opt("error"), depth + 1)
                    ?: extract(value.opt("errors"), depth + 1)
                    ?: value.keys().asSequence().filter { it.toIntOrNull() != null }
                        .mapNotNull { extract(value.opt(it), depth + 1) }.firstOrNull()
            }
            is JSONArray -> (0 until value.length()).asSequence()
                .mapNotNull { extract(value.opt(it), depth + 1) }.firstOrNull()
            else -> null
        }
    }
}
