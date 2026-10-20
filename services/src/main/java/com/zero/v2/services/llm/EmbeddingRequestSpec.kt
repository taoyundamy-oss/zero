package com.zero.v2.services.llm

import com.zero.v2.services.embedding.EmbeddingPurpose
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Embedding transport contract; kept separate from chat/provider configuration. */
internal data class EmbeddingRequestSpec(val url: String, val body: JSONObject, val native: Boolean) {
    companion object {
        fun create(
            baseUrl: String, model: String, texts: List<String>, purpose: EmbeddingPurpose,
        ): EmbeddingRequestSpec {
            val nativeUrl = nativeUrl(baseUrl, model)
            val body = JSONObject().put("model", model)
            if (nativeUrl != null) {
                body.put("input", JSONObject().put("texts", JSONArray(texts)))
                    .put("parameters", JSONObject().put("text_type", purpose.wireValue).put("output_type", "dense"))
            } else body.put("input", JSONArray(texts))
            return EmbeddingRequestSpec(nativeUrl ?: "${baseUrl.trimEnd('/')}/embeddings", body, nativeUrl != null)
        }

        /** Only documented first-party endpoints; never redirect credentials to another host. */
        fun nativeUrl(baseUrl: String, model: String): String? {
            val uri = runCatching { URI(baseUrl) }.getOrNull()
            return if (uri != null && supported(uri, model)) {
                URI("https", null, uri.host.lowercase(), uri.port,
                    "/api/v1/services/embeddings/text-embedding/text-embedding", null, null).toString()
            } else null
        }

        private fun supported(uri: URI, model: String): Boolean {
            val host = uri.host?.lowercase().orEmpty()
            val official = host in OFFICIAL_HOSTS || host.matches(WORKSPACE_HOST)
            val cleanAuthority = uri.scheme == "https" && uri.userInfo == null && uri.port in HTTPS_PORTS
            val cleanPath = uri.query == null && uri.fragment == null && uri.path.orEmpty().trimEnd('/') in API_PATHS
            return official && cleanAuthority && cleanPath && model in SUPPORTED_MODELS
        }

        fun identity(baseUrl: String, model: String): String {
            val base = "${baseUrl.trimEnd('/')}|$model"
            return if (nativeUrl(baseUrl, model) == null) base else "$base|dashscope-query-document:v1"
        }

        private val OFFICIAL_HOSTS = setOf(
            "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com",
        )
        private val WORKSPACE_HOST =
            Regex("ws-[a-z0-9-]+\\.(cn-beijing|ap-southeast-1|us-east-1)\\.maas\\.aliyuncs\\.com")
        private const val HTTPS_PORT = 443
        private val HTTPS_PORTS = setOf(-1, HTTPS_PORT)
        private val API_PATHS = setOf("/compatible-mode/v1", "/api/v1")
        private val SUPPORTED_MODELS = setOf(
            "text-embedding-v1", "text-embedding-v2", "text-embedding-v3", "text-embedding-v4",
        )
    }
}
