package com.zero.v2.services.mcp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/**
 * MCP Streamable HTTP 传输层 — Step 8.2（D57 自研轻量客户端）。
 *
 * 负责：POST JSON-RPC 到单端点 + 响应解析（双形态兼容）+ Mcp-Session-Id 会话头管理。
 *
 * 响应两种形态（MCP Streamable HTTP spec）：
 *   - application/json → 单 JSON-RPC 响应
 *   - text/event-stream → SSE 流（逐事件 data: 行携带 JSON-RPC 消息）
 *
 * 不做 stdio（Android 无子进程）；不做 GET SSE 长连接（仅客户端发起调用）。
 * 零新增依赖（OkHttp + org.json 均为项目现有）。
 */
class McpTransport(
    private val baseUrl: String,
    private val apiKey: String? = null
) {

    companion object {
        /** MCP 协议版本（2025-03-26 spec） */
        const val PROTOCOL_VERSION = "2025-03-26"
        private const val CLIENT_NAME = "ZeroV2"
        private const val CLIENT_VERSION = "2.0.0"
        private const val HEADER_SESSION = "Mcp-Session-Id"
        private val JSON_MEDIA = "application/json".toMediaType()
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }

    // P-ICP（C0.2）：zero-cloud 自签证书指纹固定——实现抽至 ZeroCloudTrust（C3.3：
    // download_file 文件下载通道同样需要 pin，单一权威），本类仅消费。
    private val http: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // The per-call deadline also covers SSE body reads.
        if (ZeroCloudTrust.isPinned(baseUrl)) ZeroCloudTrust.install(builder)
        builder.build()
    }

    /** 服务端分配的会话 ID（initialize 后携带在后续请求头） */
    @Volatile
    var sessionId: String? = null
        private set

    /** JSON-RPC 请求 ID 自增 */
    private val rpcId = AtomicInteger()

    /**
     * 发送 JSON-RPC 请求并解析响应。
     * @return 成功 = JSONObject（含 result 或 error 字段）；失败抛异常
     */
    suspend fun rpc(method: String, params: JSONObject? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): JSONObject {
        require(timeoutMs > 0) { "MCP timeout must be positive" }
        val requestId = rpcId.incrementAndGet()
        val notification = method.startsWith("notifications/")
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            if (!notification) put("id", requestId)
            put("method", method)
            params?.let { put("params", it) }
        }
        val request = Request.Builder()
            .url(baseUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply {
                sessionId?.let { header(HEADER_SESSION, it) }
                apiKey?.let { if (it.isNotBlank()) header("Authorization", "Bearer $it") }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = http.newCall(request).apply { timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching { response.use { resp ->
                        resp.header(HEADER_SESSION)?.let { sessionId = it }
                        check(resp.isSuccessful) { "MCP HTTP ${resp.code}" }
                        if (notification) JSONObject() else McpResponseReader.read(
                            resp.body ?: error("MCP 空响应体"), requestId)
                    } }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    /** 构建 initialize 请求参数 */
    fun initializeParams(): JSONObject = JSONObject().apply {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", JSONObject())
        put("clientInfo", JSONObject().apply {
            put("name", CLIENT_NAME)
            put("version", CLIENT_VERSION)
        })
    }
}
