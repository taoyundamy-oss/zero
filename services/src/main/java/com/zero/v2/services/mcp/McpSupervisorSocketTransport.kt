package com.zero.v2.services.mcp

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

/** Termux owns the socket connection; the supervisor checks module and generation ownership. */
class McpSupervisorSocketTransport(
    private val moduleId: String,
    private val endpoint: String,
    private val supervisorPort: Int,
    private val authorize: (Request) -> Request,
) : McpChannel {
    private val ids = AtomicInteger()
    override val sessionId: String? = null

    override suspend fun rpc(method: String, params: JSONObject?, timeoutMs: Long): JSONObject {
        require(timeoutMs in 1L..MAX_TIMEOUT_MS)
        require(moduleId.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}")))
        val id = ids.incrementAndGet()
        val message = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        params?.let { message.put("params", it) }
        val body = JSONObject().put("endpoint", endpoint).put("request", message).put("timeout_ms", timeoutMs)
        val request = Request.Builder().url("http://127.0.0.1:$supervisorPort/module/$moduleId/rpc")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = http.newCall(authorize(request)).apply { timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use {
                        check(it.isSuccessful) { "MCP supervisor HTTP ${it.code}" }
                        McpResponseReader.read(it.body ?: error("MCP supervisor empty response"), id)
                    } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    companion object {
        private const val MAX_TIMEOUT_MS = 3_600_000L
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .readTimeout(MAX_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }
}
