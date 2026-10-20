package com.zero.v2.services.llm

import com.zero.v2.core.common.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded, hash-only request continuity evidence. These are message counts, never provider cache tokens. */
@Singleton
class CacheRequestDiagnostics @Inject constructor(private val store: CacheDiagnosticsStore?) {
    constructor() : this(null)
    private data class Item(
        val hash: String, val fields: Map<String, String>,
        val identity: String = "none", val source: String = "wire",
    )
    private data class Snapshot(val header: Item, val messages: List<Item>, val logical: List<Item>)
    private val previous = linkedMapOf<String, Snapshot>()
    private var loaded = false
    private val restored = mutableSetOf<String>()

    @Synchronized
    fun describe(key: String, body: JSONObject, logicalMessages: List<ChatMessage> = emptyList()): String {
        load()
        val scope = hash(key)
        val field = when { body.has("input") -> "input"; body.has("contents") -> "contents"; else -> "messages" }
        val headers = JSONObject(body.toString()).apply { remove(field) }
        val messages = body.getJSONArray(field)
        val current = Snapshot(
            item(headers), (0 until minOf(messages.length(), MAX_ITEMS)).map { item(messages.get(it)) },
            logicalMessages.take(MAX_ITEMS).map(::logicalItem),
        )
        val baseline = if (restored.remove(scope)) "restored" else "memory"
        val old = previous.put(scope, current)
        while (previous.size > MAX_STREAMS) previous.remove(previous.keys.first())
        val saved = store?.write(snapshot())
        val shared = if (old?.header == current.header) {
            old.messages.zip(current.messages).takeWhile { (a, b) -> a.hash == b.hash }.size
        } else 0
        val reason = reason(old, current, shared, messages.length())
        return "request_prefix: reason=$reason sharedMessages=$shared " +
            "previousMessages=${old?.messages?.size ?: 0} messages=${messages.length()}" +
            wireDiff(reason, shared, old, current) + logicalDiff(old, current) +
            " baseline=${if (old == null) "none" else baseline} persisted=${saved ?: "disabled"}"
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val raw = store?.read() ?: return
        try { restore(raw) }
        catch (_: org.json.JSONException) { previous.clear() }
        catch (_: IllegalArgumentException) { previous.clear() }
    }

    internal fun snapshot(): String {
        fun encode(item: Item) = JSONObject().put("hash", item.hash).put("fields", JSONObject(item.fields))
            .put("identity", item.identity).put("source", item.source)
        return JSONObject().put("version", 1).put("rows", JSONArray(previous.map { (scope, entry) ->
            JSONObject().put("scope", scope).put("header", encode(entry.header))
                .put("messages", JSONArray(entry.messages.map(::encode)))
                .put("logical", JSONArray(entry.logical.map(::encode)))
        })).toString()
    }

    internal fun restore(raw: String) {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("rows")
        require(rows.length() <= MAX_STREAMS)
        fun decode(obj: JSONObject): Item {
            val fields = obj.getJSONObject("fields")
            val values = fields.keys().asSequence().associateWith { fields.getString(it) }
            require(values.all { (key, value) -> (key in SAFE_FIELDS || key.startsWith("other_")) && isHash(value) })
            val digest = obj.getString("hash")
            val identity = obj.getString("identity")
            val source = obj.getString("source")
            require(isHash(digest) && (identity == "none" || identity.matches(Regex("[a-f0-9]{16}"))))
            require(source == "wire" || source.matches(Regex("[A-Z_]+:[A-Z_]+")))
            return Item(digest, values, identity, source)
        }
        fun items(array: JSONArray): List<Item> {
            require(array.length() <= MAX_ITEMS)
            return (0 until array.length()).map { decode(array.getJSONObject(it)) }
        }
        val entries = (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            val scope = row.getString("scope").also { require(isHash(it)) }
            scope to Snapshot(decode(row.getJSONObject("header")),
                items(row.getJSONArray("messages")), items(row.getJSONArray("logical")))
        }
        previous.clear()
        previous.putAll(entries)
        restored.clear()
        restored.addAll(entries.keys)
    }


    private fun reason(old: Snapshot?, current: Snapshot, shared: Int, size: Int): String = when {
            old == null -> "first_request"
            old.header != current.header -> "tools_or_settings_changed"
            size > MAX_ITEMS || (old.messages.size == MAX_ITEMS && shared == MAX_ITEMS) -> "diagnostic_limit"
            shared == old.messages.size -> "append_only"
            else -> "history_changed"
        }
    private fun wireDiff(reason: String, shared: Int, old: Snapshot?, current: Snapshot): String = when (reason) {
            "history_changed" -> " diffIndex=$shared diffFields=" +
                changed(old?.messages?.getOrNull(shared), current.messages.getOrNull(shared))
            "tools_or_settings_changed" -> " diffIndex=header diffFields=${changed(old?.header, current.header)}"
            else -> ""
        }
    private fun logicalDiff(old: Snapshot?, current: Snapshot): String {
        val logicalIndex = old?.logical?.zip(current.logical)?.indexOfFirst { (a, b) -> a != b } ?: -1
        return if (logicalIndex >= 0) {
            val before = old!!.logical[logicalIndex]
            val after = current.logical[logicalIndex]
            " logicalIndex=$logicalIndex oldId=${before.identity} newId=${after.identity}" +
                " logicalFields=${changed(before, after)} source=${after.source}"
        } else ""
    }

    private fun logicalItem(message: ChatMessage): Item {
        val fields = mapOf("content" to message.content, "thinking" to message.thinking,
            "tool_calls" to message.toolCalls.toString(), "attachments" to message.attachments.toString(),
            "provider_content" to message.providerContent.toString(), "role" to message.role.name,
            "tool_call_id" to message.toolCallId, "name" to message.toolCallName)
            .mapValues { hash(it.value.orEmpty()) }
        return Item(hash(fields.toString()), fields, hash(message.id).take(ID_HASH_LENGTH),
            "${message.role}:${message.source}")
    }

    private fun item(value: Any?): Item = Item(hash(canonical(value)),
        if (value is JSONObject) value.keys().asSequence().associate {
            (if (it in SAFE_FIELDS) it else "other_" + hash(it)) to hash(canonical(value.get(it)))
        }
        else emptyMap())

    private fun changed(before: Item?, after: Item?): String {
        if (before == null || after == null) return "length"
        val names = (before.fields.keys + after.fields.keys).filter { before.fields[it] != after.fields[it] }
        return names.map { if (it in SAFE_FIELDS) it else "other" }.distinct().sorted()
            .joinToString(",").ifEmpty { "identity" }
    }


    private companion object {
        const val MAX_STREAMS = 8
        const val MAX_ITEMS = 4096
        const val ID_HASH_LENGTH = 16
        val SAFE_FIELDS = setOf("model", "tools", "instructions", "stream", "stream_options", "max_tokens",
            "max_output_tokens", "max_completion_tokens", "temperature", "top_p", "reasoning", "include",
            "role", "type", "content", "output", "arguments", "name", "call_id", "tool_call_id", "tool_calls",
            "thinking", "attachments", "provider_content", "encrypted_content", "id", "status")
    }
}

private fun isHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))

private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted()
        .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
