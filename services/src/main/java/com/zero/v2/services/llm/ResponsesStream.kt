package com.zero.v2.services.llm

import com.zero.v2.core.common.ProviderContentState
import com.zero.v2.core.common.StreamChunk
import com.zero.v2.core.common.ToolCallDelta
import org.json.JSONObject
import org.json.JSONArray

/** Tools are released only with a completed response. Never log encrypted reasoning. */
internal class ResponsesStream(private val model: String, private val scope: String,
    private val namespace: String? = null) {
    var completed = false
        private set
    private val text = StringBuilder()
    private val completedItems = sortedMapOf<Int, JSONObject>()

    fun decode(event: JSONObject): List<StreamChunk> {
        check(!completed) { "responses_event_after_completion" }
        return when (event.getString("type")) {
            "response.output_text.delta", "response.refusal.delta" -> {
                val delta = event.getString("delta")
                text.append(delta)
                listOf(StreamChunk(contentDelta = delta))
            }
            "response.output_item.done" -> {
                if (namespace != null) {
                    val index = event.getInt("output_index")
                    require(index >= 0 && !completedItems.containsKey(index)) { "responses_duplicate_output_item" }
                    completedItems[index] = event.getJSONObject("item")
                }
                emptyList()
            }
            "response.completed" -> completed(event.getJSONObject("response"))
            "response.failed", "response.incomplete", "error" ->
                throw ResponsesFailure(event.optJSONObject("response"), event)
            in metadataEvents -> emptyList()
            else -> error("responses_event_unsupported")
        }
    }

    private fun completed(response: JSONObject): List<StreamChunk> = try { finish(response) }
        catch (_: IllegalArgumentException) {
            throw ResponsesFailure(response, JSONObject().put("code", "invalid_response"))
        } catch (_: IllegalStateException) {
            throw ResponsesFailure(response, JSONObject().put("code", "invalid_response"))
        } catch (_: org.json.JSONException) {
            throw ResponsesFailure(response, JSONObject().put("code", "invalid_response"))
        }

    private fun finish(response: JSONObject): List<StreamChunk> {
        require(response.getString("status") == "completed") { "responses_not_completed" }
        val terminal = response.getJSONArray("output")
        val output = if (namespace != null && terminal.length() == 0 && completedItems.isNotEmpty()) {
            require(completedItems.keys.toList() == (0 until completedItems.size).toList()) {
                "responses_missing_output_item"
            }
            JSONArray(completedItems.values.toList())
        } else terminal
        require(ResponsesProtocol.text(output) == text.toString()) { "responses_stream_text_mismatch" }
        val calls = ResponsesProtocol.objects(output).filter { it.getString("type") == "function_call" }
        require(calls.map { it.getString("call_id") }.distinct().size == calls.size) { "responses_duplicate_call" }
        calls.forEach {
            if (namespace != null) require(it.optString("namespace") == namespace) {
                "chatgpt_tool_namespace_mismatch"
            }
            require(it.getString("call_id").isNotBlank() && it.getString("name").isNotBlank() &&
                it.optString("status", "completed") == "completed") { "responses_invalid_call" }
            JSONObject(it.getString("arguments"))
        }
        val usage = response.optJSONObject("usage") ?: JSONObject()
        completed = true
        return buildList {
            add(StreamChunk(providerContent = ProviderContentState(scope, output.toString())))
            calls.forEachIndexed { index, call ->
                add(StreamChunk(toolCallDelta = ToolCallDelta(index, call.getString("call_id"),
                    call.getString("name"), call.getString("arguments"))))
            }
            add(StreamChunk(finishReason = if (calls.isEmpty()) "stop" else "tool_calls",
                tokenUsage = ProviderUsage.read(usage, model, responses = true)))
        }
    }

    private companion object {
        val metadataEvents = setOf(
            "response.created", "response.in_progress", "response.queued",
            "response.output_item.added", "response.output_item.done",
            "response.content_part.added", "response.content_part.done", "response.output_text.done",
            "response.output_text.annotation.added", "response.refusal.done",
            "response.function_call_arguments.delta", "response.function_call_arguments.done",
            "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
            "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
            "response.reasoning_text.delta", "response.reasoning_text.done",
        )
    }
}
