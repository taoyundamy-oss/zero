package com.zero.v2.services.mcp

import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Return at the matching SSE event; a tool result does not depend on the server closing its stream. */
internal object McpResponseReader {
    private const val MAX_EVENT_BYTES = 16L * 1024 * 1024

    fun read(body: ResponseBody, requestId: Int): JSONObject =
        if (body.contentType()?.subtype != "event-stream") {
            val source = body.source()
            source.request(MAX_EVENT_BYTES + 1)
            check(source.buffer.size <= MAX_EVENT_BYTES) { "MCP 响应超过16MB" }
            response(source.readUtf8(), requestId) ?: error("MCP 响应缺少匹配请求ID=$requestId 的结果")
        } else readEvents(body, requestId)

    private fun readEvents(body: ResponseBody, requestId: Int): JSONObject {
        val source = body.source()
        val data = StringBuilder()
        while (!source.exhausted()) {
            val newline = source.indexOf('\n'.code.toByte(), 0, MAX_EVENT_BYTES + 1)
            check(newline >= 0 || source.buffer.size <= MAX_EVENT_BYTES) { "MCP SSE事件超过16MB" }
            val line = if (newline >= 0) source.readUtf8(newline + 1).trimEnd('\r', '\n') else source.readUtf8()
            if (line.isEmpty()) {
                response(data.toString(), requestId)?.let { return it }
                data.clear()
            } else if (line.startsWith("data:")) {
                data.append(line.removePrefix("data:").trimStart()).append('\n')
                check(data.length <= MAX_EVENT_BYTES) { "MCP SSE事件超过16MB" }
            }
        }
        return response(data.toString(), requestId) ?: error("MCP 流在返回结果前关闭: id=$requestId")
    }

    private fun response(text: String, requestId: Int): JSONObject? = try {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val batch = JSONArray(trimmed)
            (0 until batch.length()).mapNotNull { batch.optJSONObject(it) }.firstOrNull { matches(it, requestId) }
        } else JSONObject(trimmed).takeIf { matches(it, requestId) }
    } catch (_: JSONException) { null }

    private fun matches(value: JSONObject, id: Int): Boolean =
        (value.has("result") || value.has("error")) && (!value.has("id") || value.optInt("id", -1) == id)
}
