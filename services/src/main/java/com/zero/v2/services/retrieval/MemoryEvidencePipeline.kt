package com.zero.v2.services.retrieval

import com.zero.v2.services.llm.ILanguageModel
import com.zero.v2.services.llm.ModelCapability
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Explicit opt-in pipeline. Does not select/fallback models, mutate memory, or cache decisions. */
@Singleton
class MemoryEvidencePipeline internal constructor(
    private val reader: MemoryEvidenceReader,
    private val now: () -> Long,
    private val budgetMs: Long,
) {
    @Inject constructor(reader: MemoryEvidenceReader) : this(reader, System::currentTimeMillis, DEADLINE_MS)

    /** Consumers must use text, never reload a shorter memory fragment from memoryId. */
    data class Entry(
        val memoryId: String, val use: EvidenceSupport.Use, val reason: String,
        val text: String? = null, val evidenceKind: String? = null, val scope: String? = null,
    )
    data class Result(val entries: List<Entry>, val status: String) {
        val completed: Boolean get() = status == "applied"
    }
    private val lock = Mutex()

    suspend fun select(
        query: String, ids: List<String>, model: ILanguageModel, history: Boolean = false,
        scope: String = "personal",
    ): Result = when {
        invalid(query, ids) || scope.isBlank() || scope.length > MAX_SCOPE -> failure("invalid_input")
        !lock.tryLock() -> failure("busy")
        else -> try {
            withTimeoutOrNull(budgetMs) { execute(query, ids, model, history, scope) } ?: failure("timeout")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure("unavailable")
        } finally { lock.unlock() }
    }

    private fun invalid(query: String, ids: List<String>): Boolean {
        val badSize = query.isBlank() || query.length > MAX_QUERY || ids.isEmpty() || ids.size > MAX_CANDIDATES
        return badSize || ids.any(String::isBlank) || ids.distinct().size != ids.size
    }

    private suspend fun execute(
        query: String, ids: List<String>, model: ILanguageModel, history: Boolean, scope: String,
    ): Result {
        val identity = model.retrievalIdentity
        if (!available(model)) return failure("model_unavailable")
        val captures = ids.map { id ->
            val capture = reader.capture(id)
            id to if (capture.ticket != null && capture.ticket.scope != scope) {
                MemoryEvidenceReader.Capture(null, "scope_mismatch")
            } else capture
        }
        val tickets = captures.mapNotNull { it.second.ticket }
        return if (tickets.isEmpty()) failure("no_verified_sources") else {
            infer(query, captures, model, history, identity)
        }
    }

    private suspend fun infer(
        query: String, captures: List<Pair<String, MemoryEvidenceReader.Capture>>,
        model: ILanguageModel, history: Boolean, identity: String,
    ): Result {
        if (!sameModel(model, identity)) return failure("model_changed")
        val tickets = captures.mapNotNull { it.second.ticket }
        val payload = JSONObject().put("query", query).put("history", history)
            .put("candidates", JSONArray(tickets.mapIndexed { slot, ticket ->
                JSONObject().put("slot", slot).put("text", ticket.text)
            })).toString()
        val raw = model.boundedCompletion(payload, MemoryEvidencePrompt.TEXT)
        currentCoroutineContext().ensureActive()
        val selected = raw?.let { EvidenceSupport.decode(it, tickets.size) }
        return when {
            !sameModel(model, identity) -> failure("model_changed")
            selected == null -> failure("invalid_response")
            else -> recheck(captures, selected, history).let {
                if (sameModel(model, identity)) it else failure("model_changed")
            }
        }
    }

    private suspend fun recheck(
        captures: List<Pair<String, MemoryEvidenceReader.Capture>>,
        selected: EvidenceSupport.Selection, history: Boolean,
    ): Result {
        val tickets = captures.mapNotNull { it.second.ticket }
        val decisions = tickets.mapIndexed { slot, ticket ->
            val use = when (slot) {
                in selected.certain -> EvidenceSupport.Use.CERTAIN
                in selected.uncertain -> EvidenceSupport.Use.UNCERTAIN
                else -> EvidenceSupport.Use.NONE
            }
            val decision = reader.revalidate(ticket, use, EvidenceSupport.QueryScope(now(), history))
            val supported = decision.use != EvidenceSupport.Use.NONE
            Entry(ticket.memoryId, decision.use, decision.reason,
                ticket.text.takeIf { supported }, ticket.evidenceKind.takeIf { supported },
                ticket.scope.takeIf { supported })
        }.associateBy { it.memoryId }
        currentCoroutineContext().ensureActive()
        return Result(captures.map { (id, capture) ->
            decisions[id] ?: Entry(id, EvidenceSupport.Use.NONE, capture.reason)
        }, "applied")
    }

    private fun sameModel(model: ILanguageModel, identity: String) =
        available(model) && model.retrievalIdentity == identity

    private fun available(model: ILanguageModel) = model.enabled && model.keyConfigured &&
        model.contextWindow >= MIN_CONTEXT && ModelCapability.CHAT in model.capabilities

    private fun failure(status: String) = Result(emptyList(), status)

    companion object {
        const val DEADLINE_MS = 2500L
        private const val MAX_QUERY = 1200
        private const val MAX_CANDIDATES = 8
        private const val MAX_SCOPE = 100
        private const val MIN_CONTEXT = 8192
    }
}
