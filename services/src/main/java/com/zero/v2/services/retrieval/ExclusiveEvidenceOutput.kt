package com.zero.v2.services.retrieval

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Opt-in candidate protocol. One enum per slot makes overlapping support labels impossible.
 * Does not select a model or change the existing production prompt/decoder.
 */
object ExclusiveEvidenceOutput {
    private const val MAX_SLOTS = 8
    private const val MAX_CHARS = 2048
    private val labels = setOf("certain", "uncertain", "none")
    private const val SPACE = "[ \\t\\r\\n]*"
    private val entry = "\"[0-7]\"$SPACE:$SPACE\"(?:certain|uncertain|none)\""
    // Restrict wire spelling to literal schema keys/labels; JSONTokener alone accepts comments/NUL.
    private val syntax = Regex("$SPACE\\{$SPACE$entry$SPACE(?:,$SPACE$entry$SPACE)*}$SPACE")

    fun schema(count: Int): JSONObject {
        require(count in 1..MAX_SLOTS)
        val keys = (0 until count).map(Int::toString)
        val properties = JSONObject()
        keys.forEach { key ->
            properties.put(key, JSONObject().put("type", "string").put("enum", JSONArray(labels.toList())))
        }
        return JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(keys)).put("additionalProperties", false)
    }

    /** Parse tokens explicitly: Android JSONObject may silently overwrite duplicate keys. */
    fun decode(raw: String, count: Int): EvidenceSupport.Selection? = runCatching {
        require(count in 1..MAX_SLOTS && raw.length <= MAX_CHARS && syntax.matches(raw))
        val tokenizer = JSONTokener(raw)
        require(tokenizer.nextClean() == '{')
        val seen = mutableSetOf<String>()
        val certain = mutableListOf<Int>()
        val uncertain = mutableListOf<Int>()
        repeat(count) { index ->
            // JSONTokener accepts some JavaScript-like syntax; candidate protocol requires quoted strings.
            require(tokenizer.nextClean() == '"')
            val key = tokenizer.nextString('"')
            require(key in (0 until count).map(Int::toString) && seen.add(key))
            require(tokenizer.nextClean() == ':' && tokenizer.nextClean() == '"')
            val label = tokenizer.nextString('"')
            require(label in labels)
            when (label) {
                "certain" -> certain.add(key.toInt())
                "uncertain" -> uncertain.add(key.toInt())
            }
            require(tokenizer.nextClean() == if (index == count - 1) '}' else ',')
        }
        require(tokenizer.nextClean() == '\u0000')
        EvidenceSupport.Selection(certain.sorted(), uncertain.sorted())
    }.getOrNull()
}
