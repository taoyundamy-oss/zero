package com.zero.v2.services.chatgpt

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Independent client: no provider interceptors, redirects, custom bases, or credential-bearing logs. */
internal class ChatGptHttp {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    suspend fun jwks(): JSONObject = json(Request.Builder().url("${ChatGptOAuth.ISSUER}/.well-known/jwks.json").build())
    suspend fun models(access: String): JSONObject = json(Request.Builder()
        .url("https://api.openai.com/v1/models").header("Authorization", "Bearer $access").build())
    suspend fun exchange(a: ChatGptOAuth.Attempt, callback: ChatGptOAuth.Callback): JSONObject = token(mapOf(
        "grant_type" to "authorization_code", "client_id" to callback.client,
        "code" to requireNotNull(callback.code), "code_verifier" to a.verifier, "redirect_uri" to a.redirect,
        "resource" to ChatGptOAuth.RESOURCE))
    suspend fun refresh(account: JSONObject): JSONObject = token(mapOf("grant_type" to "refresh_token",
        "client_id" to account.getString("client_id"), "refresh_token" to account.getString("refresh_token"),
        "resource" to ChatGptOAuth.RESOURCE))
    suspend fun revoke(account: JSONObject): Boolean {
        if (account.optString("refresh_token").isBlank()) return true
        val metadata = json(Request.Builder().url("${ChatGptOAuth.ISSUER}/.well-known/openid-configuration").build())
        require(metadata.getString("issuer") == ChatGptOAuth.ISSUER)
        require(metadata.getString("revocation_endpoint") == "${ChatGptOAuth.ISSUER}/api/accounts/oauth/revoke")
        val request = Request.Builder().url(metadata.getString("revocation_endpoint")).post(form(mapOf(
            "token" to account.getString("refresh_token"), "token_type_hint" to "refresh_token",
            "client_id" to account.getString("client_id")))).build()
        return execute(request) { it.code == java.net.HttpURLConnection.HTTP_OK }
    }
    private suspend fun token(fields: Map<String, String>) = json(Request.Builder()
        .url("${ChatGptOAuth.ISSUER}/api/accounts/oauth/token").post(form(fields)).build())
    private fun form(fields: Map<String, String>) = FormBody.Builder().apply {
        fields.forEach { (key, value) -> add(key, value) }
    }.build()
    private suspend fun json(request: Request): JSONObject = execute(request) {
        if (!it.isSuccessful) {
            val error = runCatching { JSONObject(it.peekBody(MAX_RESPONSE).string()) }.getOrNull()
            throw ChatGptTokenFailure(it.code, error)
        }
        val source = requireNotNull(it.body).source()
        val buffer = okio.Buffer()
        while (source.read(buffer, READ_CHUNK) != -1L) require(buffer.size <= MAX_RESPONSE)
        JSONObject(buffer.readUtf8())
    }
    private suspend fun <T> execute(request: Request, read: (okhttp3.Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use(read).also { currentCoroutineContext().ensureActive() }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally { cancellation.cancel(); call.cancel() }
    }
    companion object {
        private const val TIMEOUT_SECONDS = 20L
        private const val MAX_RESPONSE = 1048576L
        private const val READ_CHUNK = 8192L
    }
}
