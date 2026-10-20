package com.zero.v2.services.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Exact observed prefix + conservative new suffix. Never derives a global tokenizer ratio. */
@Singleton
class RequestContextBudget @Inject constructor(private val store: RequestBudgetStore?) {
    constructor() : this(null)
    private data class Observation(val header: String, val items: List<String>, val input: Long)
    private val observations = linkedMapOf<String, Observation>()
    private var loaded = false

    data class Estimate(val tokens: Int, val source: String, val previousInput: Long?, val tail: Long) {
        fun diagnostic() = "source=$source estimate=$tokens previousInput=${previousInput ?: 0} tail=$tail"
    }

    fun estimate(scope: String, body: JSONObject): Int = inspect(scope, body).tokens

    @Synchronized
    fun inspect(scope: String, body: JSONObject): Estimate {
        load()
        val parts = parts(body)
        val old = observations[scope]
        val reason = when {
            old == null -> "no_observation"
            old.header != digest(canonical(parts.first)) -> "header_changed"
            parts.second.size < old.items.size -> "history_shortened"
            old.items.indices.any { old.items[it] != digest(canonical(parts.second[it])) } -> "history_changed"
            else -> "observed_prefix"
        }
        val matched = reason == "observed_prefix"
        val tail = if (matched) parts.second.drop(old!!.items.size).sumOf { rough(canonical(it)) }
            else parts.second.sumOf { rough(canonical(it)) }
        val estimate = if (matched) old!!.input + tail else rough(canonical(parts.first)) + tail + FRAMING
        return Estimate(estimate.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), reason, old?.input, tail)
    }

    @Synchronized
    fun observe(scope: String, body: JSONObject, inputTokens: Long) {
        if (inputTokens !in 1..Int.MAX_VALUE.toLong()) return
        load()
        val (header, items) = parts(body)
        if (items.size > MAX_ITEMS) return
        observations.remove(scope)
        observations[scope] = Observation(digest(canonical(header)), items.map { digest(canonical(it)) }, inputTokens)
        while (observations.size > MAX_SCOPES) observations.remove(observations.keys.first())
        store?.write(snapshot())
    }

    fun requireFits(scope: String, body: JSONObject, contextWindow: Int, outputReserve: Int) {
        val input = estimate(scope, body)
        if (input.toLong() + outputReserve.coerceAtLeast(0) + contextWindow.coerceAtLeast(0) / SAFETY_DIVISOR >
            contextWindow.coerceAtLeast(0)) {
            throw IOException("REQUEST_CONTEXT_LIMIT: inputEstimate=$input output=$outputReserve limit=$contextWindow")
        }
    }

    @Synchronized
    internal fun snapshot(): String = JSONObject().put("version", 1).put("observations",
        JSONArray(observations.map { (scope, value) -> JSONObject().put("scope", scope)
            .put("header", value.header).put("items", JSONArray(value.items)).put("input", value.input) })).toString()

    @Synchronized
    internal fun restore(raw: String) {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("observations")
        require(rows.length() <= MAX_SCOPES)
        val restored = linkedMapOf<String, Observation>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val items = row.getJSONArray("items")
            val input = row.getLong("input")
            require(items.length() <= MAX_ITEMS && input in 1..Int.MAX_VALUE.toLong())
            restored[row.getString("scope")] = Observation(row.getString("header"),
                (0 until items.length()).map { items.getString(it) }, input)
        }
        observations.clear()
        observations.putAll(restored)
        loaded = true
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try { store?.read()?.let(::restore) } catch (_: IllegalArgumentException) { observations.clear() }
        catch (_: org.json.JSONException) { observations.clear() }
    }

    private fun parts(body: JSONObject): Pair<JSONObject, List<Any>> {
        val field = when { body.has("input") -> "input"; body.has("contents") -> "contents"; else -> "messages" }
        val header = JSONObject(body.toString()).apply {
            remove(field)
            // Output changes must recheck the reserve, but do not change input tokenization.
            listOf("stream", "stream_options", "max_tokens", "max_completion_tokens", "max_output_tokens")
                .forEach(::remove)
        }
        val items = body.getJSONArray(field)
        return header to (0 until items.length()).map { items.get(it) }
    }

    private fun rough(text: String): Long = text.fold(0L) { count, char ->
        count + if (char.code > ASCII_MAX) TEXT_DIVISOR else 1
    } / TEXT_DIVISOR + ITEM_FRAMING

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    companion object {
        private const val MAX_SCOPES = 8
        private const val MAX_ITEMS = 4096
        private const val FRAMING = 256
        private const val ITEM_FRAMING = 4
        private const val TEXT_DIVISOR = 2
        private const val ASCII_MAX = 127
        private const val SAFETY_DIVISOR = 20
        internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
