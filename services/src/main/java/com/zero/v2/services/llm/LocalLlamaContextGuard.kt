package com.zero.v2.services.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Explicit llama.cpp profile: count the real rendered prompt before generation, fail closed. */
internal class LocalLlamaContextGuard(client: OkHttpClient) {
    private val http = client.newBuilder().callTimeout(PREFLIGHT_SECONDS, TimeUnit.SECONDS).build()

    fun prepare(profile: LlmProfile, body: JSONObject, key: String? = null) {
        if (!profile.localLlamaPreflight) return
        require(profile.effectiveGatewayMode() == GatewayMode.DIRECT) { "llama.cpp 预检仅用于直连部署" }
        val headers = CloudflareChatTransport.request(profile, key).build().headers
        LocalLlamaMessageProjection.apply(body)
        if (profile.contractId == "generic-v1") {
            body.put("chat_template_kwargs", JSONObject().put("enable_thinking", profile.enableThinking))
        }
        body.put("cache_prompt", true)
        if (body.optBoolean("stream")) body.put("stream_options", JSONObject().put("include_usage", true))
        val root = profile.baseUrl.trimEnd('/').removeSuffix("/v1")
        val props = request("$root/props", headers)
        val context = props.getJSONObject("default_generation_settings").getInt("n_ctx")
        val prompt = request("$root/apply-template", headers, body).getString("prompt")
        val tokens = request("$root/tokenize", headers, JSONObject().put("content", prompt).put("add_special", false))
            .getJSONArray("tokens").length()
        val limit = minOf(context, profile.contextWindow)
        val output = body.getInt(ModelContracts.get(profile.contractId).outputField)
        if (tokens.toLong() + output + FRAMING_RESERVE > limit) {
            throw IOException("LOCAL_CONTEXT_LIMIT: input=$tokens output=$output limit=$limit；请缩短本轮内容或开启新会话")
        }
    }

    private fun request(url: String, headers: okhttp3.Headers, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(url).headers(headers)
        body?.let { builder.post(it.toString().toRequestBody(JSON_MEDIA)) }
        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("local_preflight_http_${response.code}")
            JSONObject(response.body?.string() ?: throw IOException("local_preflight_empty"))
        }
    }

    companion object {
        const val PROFILE_ID = "local-qwen"
        private const val PREFLIGHT_SECONDS = 5L
        private const val FRAMING_RESERVE = 64
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
