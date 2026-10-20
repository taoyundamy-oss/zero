package com.zero.v2.services.llm

import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.data.dao.UsageRequestDao
import com.zero.v2.core.data.entity.UsageRequestEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Await writes at request boundaries; cancellation cannot discard the terminal observation. */
@Singleton
class UsageLedger @Inject constructor(private val dao: UsageRequestDao,
    private val failureStore: UsageLedgerFailureStore? = null) {
    private val mutex = Mutex()
    private val _incomplete = MutableStateFlow(failureStore?.failed() == true)
    val incomplete = _incomplete.asStateFlow()

    suspend fun begin(id: String, profile: LlmProfile, kind: String, continuity: String, budgetSource: String) =
        write {
            dao.insert(UsageRequestEntity(id = id, startedAt = System.currentTimeMillis(),
                connectionId = profile.connectionId.ifBlank { profile.id }, profileId = profile.id,
                model = profile.modelId, kind = kind, continuity = continuity, budgetSource = budgetSource,
                local = profile.localLlamaPreflight))
        }

    suspend fun finish(id: String, status: String, usage: TokenUsage?) = write {
        val terminal = if (status in setOf("completed", "cancelled")) status else "failed"
        dao.complete(id, System.currentTimeMillis(), terminal, usage?.takeIf { it.inputObserved }?.inputTokens,
            usage?.takeIf { it.inputObserved && it.cacheUnknownSamples == 0L }?.cachedTokens,
            usage?.takeIf { it.outputObserved }?.outputTokens)
    }

    private suspend fun write(block: suspend () -> Unit) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            try { block() }
            catch (_: Exception) {
                _incomplete.value = true
                failureStore?.mark()
            }
        }
    }
}
