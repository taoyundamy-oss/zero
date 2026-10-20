package com.zero.v2.services.mcp

import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 协议客户端 — Step 8.2（D57 自研轻量）。
 *
 * 协议三调用：initialize（握手）→ tools/list（发现）→ tools/call（执行）。
 * 每次调用带超时；错误转用户可读文案；全程 `mcp_proto` 埋点。
 *
 * 生命周期：实例绑定一个 MCP 服务器端点；initialize 后 sessionId 由 Transport 管理。
 */
class McpClient private constructor(
    private val serverName: String,
    channel: McpChannel,
    private val traceManager: AgentTraceManager,
    private val resultDecoder: McpResultDecoder,
) {

    /** 既有构造（HTTP 主路，D59）：路由 url——内部建 McpTransport（Step 8.2 行为不变） */
    constructor(serverName: String, url: String, apiKey: String? = null, traceManager: AgentTraceManager,
        resultDecoder: McpResultDecoder)
        : this(serverName, McpTransport(url, apiKey).asChannel(), traceManager, resultDecoder)

    /** M2.3 构造（unix-socket 兜底，D59）：transport 注入——不走路由 url 内部建传输 */
    constructor(serverName: String, transport: McpSocketTransport, traceManager: AgentTraceManager,
        resultDecoder: McpResultDecoder)
        : this(serverName, transport as McpChannel, traceManager, resultDecoder)

    constructor(serverName: String, transport: McpSupervisorSocketTransport, traceManager: AgentTraceManager,
        resultDecoder: McpResultDecoder)
        : this(serverName, transport as McpChannel, traceManager, resultDecoder)

    companion object {
        private const val TRACE_ID = "mcp_proto"
        private const val INIT_TIMEOUT_MS = 15_000L
        private const val LIST_TIMEOUT_MS = 15_000L
        private const val CALL_TIMEOUT_MS = 60_000L
    }

    private val transport: McpChannel = channel
    private var initialized = false

    /** MCP 工具定义（tools/list 结果项） */
    data class McpTool(
        val name: String,
        val description: String,
        /** JSON Schema（parameters）——直接透传给 FunctionDefinition */
        val inputSchema: JSONObject
    )

    // ═══ 协议调用 ═══

    /**
     * initialize 握手（能力协商）。
     * @return true 成功；false 失败（错误文案见 trace）
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeout(INIT_TIMEOUT_MS) {
                val resp = transport.rpc("initialize", transport.initializeParams())
                checkRpcError(resp, "initialize")
                // 发送 initialized 通知（协议要求）
                transport.rpc("notifications/initialized")
                initialized = true
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, "mcp",
                    "init_ok: server=$serverName sessionId=${transport.sessionId?.take(16)}")
                true
            }
        } catch (e: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            traceManager.record(TRACE_ID, NodeType.ERROR, "mcp",
                "init_failed: server=$serverName ${e.message?.take(60)}")
            false
        }
    }

    /**
     * 发现服务器工具清单（tools/list）。
     * @return 工具列表；失败返回空列表
     */
    data class ToolListResult(val tools: List<McpTool>, val failed: Boolean = false)

    suspend fun toolsList(): List<McpTool> = discoverTools().tools

    suspend fun discoverTools(): ToolListResult = withContext(Dispatchers.IO) {
        if (!initialized && !initialize()) return@withContext ToolListResult(emptyList(), failed = true)
        try {
            withTimeout(LIST_TIMEOUT_MS) {
                val resp = transport.rpc("tools/list", JSONObject().put("cursor", JSONObject.NULL))
                checkRpcError(resp, "tools/list")
                val tools = resp.getJSONObject("result").getJSONArray("tools")
                val out = mutableListOf<McpTool>()
                for (i in 0 until tools.length()) {
                    val t = tools.optJSONObject(i) ?: continue
                    val name = t.optString("name")
                    if (name.isBlank()) continue
                    out.add(McpTool(
                        name = name,
                        description = t.optString("description", ""),
                        inputSchema = t.optJSONObject("inputSchema") ?: JSONObject()
                    ))
                }
                traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, "mcp",
                    "tools_listed: server=$serverName count=${out.size} [${out.joinToString(", ") { it.name }}]")
                ToolListResult(out)
            }
        } catch (e: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            traceManager.record(TRACE_ID, NodeType.ERROR, "mcp",
                "tools_list_failed: server=$serverName ${e.message?.take(60)}")
            ToolListResult(emptyList(), failed = true)
        }
    }

    /**
     * 执行工具调用（tools/call）。
     * @param timeoutMs 本次调用超时上限（M0/B-II-8 定案：默认 60s，受信长时模块可传更大值如 600_000）。
     *   **语义门（§十六封板）**：timeoutMs 只是"受信模块同步调用的延长上限"（如 3 分钟内转码），
     *   **不是长任务方案**——分钟级以上（如 10 分钟 ffmpeg）必须走 start_job→poll_status 异步 FC 模式，
     *   防 Agent 回合被同步长调用占死；该语义须随超时放宽同步写进对应 FC description（M2.2/M3.2 落地）。
     * @return 成功 = 工具返回的文本内容；失败 = 错误文案
     */
    suspend fun toolsCall(
        toolName: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long = CALL_TIMEOUT_MS
    ): com.zero.v2.core.common.FunctionResult =
        withContext(Dispatchers.IO) {
            if (!initialized && !initialize()) {
                return@withContext com.zero.v2.core.common.FunctionResult.failure("MCP 服务器 $serverName 初始化失败")
            }
            try {
                withTimeout(timeoutMs) {
                    val params = JSONObject().apply {
                        put("name", toolName)
                        put("arguments", JSONObject(arguments))
                    }
                    val resp = transport.rpc("tools/call", params, timeoutMs)
                    checkRpcError(resp, "tools/call")
                    val result = resp.optJSONObject("result")
                        ?: return@withTimeout com.zero.v2.core.common.FunctionResult.failure("MCP 响应缺少工具结果")
                    resultDecoder.decode(result)
                }
            } catch (e: Exception) {
                kotlin.coroutines.coroutineContext.ensureActive()
                traceManager.record(TRACE_ID, NodeType.ERROR, "mcp",
                    "call_failed: server=$serverName tool=$toolName ${e.message?.take(60)}")
                com.zero.v2.core.common.FunctionResult.failure("工具调用失败: ${e.message?.take(60)}")
            }
        }

    /** 服务器是否就绪 */
    fun isReady(): Boolean = initialized

    // ═══ 内部 ═══

    /** 校验 JSON-RPC error 字段（有错抛异常） */
    private fun checkRpcError(resp: JSONObject, method: String) {
        val error = resp.optJSONObject("error") ?: return
        val code = error.optInt("code", -1)
        val message = error.optString("message", "未知错误")
        throw IllegalStateException("MCP $method 错误 [$code]: $message")
    }
}
