package com.zero.v2.services.retrieval

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Staged personal-evidence contract; source identity is checked by the client, never by the LLM.
 * A matching user message proves provenance, not the truth or personal subject of its contents.
 * No production retrieval caller or persistence side effect is enabled by this object.
 */
object EvidenceSupport {
    private const val MAX_OUTPUT_CHARS = 2048
    private const val MAX_CANDIDATES = 8
    enum class Use { CERTAIN, UNCERTAIN, NONE }
    data class Anchor(val messageId: String, val conversationId: String, val bodyDigest: String)
    data class Source(val messageId: String, val conversationId: String, val body: String,
        val role: String = "unknown", val systemGenerated: Boolean = false, val deleted: Boolean = false)
    data class State(val active: Boolean = true, val superseded: Boolean = false,
        val validFrom: Long? = null, val validUntil: Long? = null, val unresolvedConflict: Boolean = false)
    data class Decision(val use: Use, val reason: String)
    data class Selection(val certain: List<Int>, val uncertain: List<Int>)
    data class QueryScope(val now: Long, val history: Boolean = false)

    fun digest(body: String): String = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun verified(anchor: Anchor?, source: Source?): Boolean = anchor != null && source != null &&
        !source.deleted && !source.systemGenerated && source.role == "user" && source.body.isNotBlank() &&
        anchor.messageId.isNotBlank() && anchor.conversationId.isNotBlank() &&
        anchor.messageId == source.messageId && anchor.conversationId == source.conversationId &&
        anchor.bodyDigest == digest(source.body)

    /** history must be explicit caller scope; never inferred from a model's support label. */
    fun assess(requested: Use, anchor: Anchor?, source: Source?, state: State, scope: QueryScope): Decision =
        if (requested == Use.NONE) Decision(Use.NONE, "no_support") else {
            assessVerified(requested, verified(anchor, source), state, scope)
        }

    /** Only client-owned source adapters may attest confirmation audits; no model-provided flags. */
    internal fun assessVerified(requested: Use, verified: Boolean, state: State, scope: QueryScope): Decision = when {
        requested == Use.NONE -> Decision(Use.NONE, "no_support")
        !verified -> Decision(Use.NONE, "source_unverified")
        state.validFrom != null && state.validUntil != null && state.validFrom >= state.validUntil ->
            Decision(Use.NONE, "invalid_interval")
        !scope.history && !current(state, scope.now) -> Decision(Use.NONE, "outside_current_scope")
        state.unresolvedConflict -> Decision(Use.UNCERTAIN, "unresolved_conflict")
        else -> Decision(requested, "semantic_judgment")
    }

    private fun current(state: State, now: Long): Boolean {
        val outsideTime = state.validUntil?.let { now >= it } == true || state.validFrom?.let { now < it } == true
        return state.active && !state.superseded && !outsideTime
    }

    /** NONE is the complement; malformed/partial protocol is null, never successful abstention. */
    fun decode(raw: String, count: Int): Selection? = runCatching {
        require(raw.length <= MAX_OUTPUT_CHARS && count in 1..MAX_CANDIDATES)
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val tokenizer = JSONTokener(text)
        val obj = tokenizer.nextValue() as? JSONObject ?: error("not_object")
        require(tokenizer.nextClean() == '\u0000')
        require(obj.keys().asSequence().toSet() == setOf("certain", "uncertain"))
        val certain = slots(obj.getJSONArray("certain"), count)
        val uncertain = slots(obj.getJSONArray("uncertain"), count)
        require((certain + uncertain).distinct().size == certain.size + uncertain.size)
        Selection(certain, uncertain)
    }.getOrNull()

    private fun slots(array: JSONArray, count: Int): List<Int> = (0 until array.length()).map { i ->
        val value = array.get(i) as? Number ?: error("non_numeric_slot")
        value.toInt().also { require(value.toDouble() == it.toDouble() && it in 0 until count) }
    }
}
