package com.zero.v2.framework.memory

import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.dao.MemoryExtractionJobDao
import com.zero.v2.core.data.dao.UserGraphDao
import com.zero.v2.core.data.entity.MemoryExtractionJobEntity
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.agent.AgentJob
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/** Durable GRAPH outbox: completion and graph mutation commit together, never a process-local cursor. */
@Singleton
class GraphExtractionJob @Inject constructor(
    private val memoryModule: MemoryModule,
    private val memoryDao: MemoryDao,
    private val graphDao: UserGraphDao,
    private val modelSelector: ModelSelector,
    private val traceManager: AgentTraceManager,
    private val jobs: MemoryExtractionJobDao,
) : AgentJob {
    companion object {
        const val MAX_MEMORIES_PER_RUN = 50
        private const val POLL_MS = 300_000L
    }
    private val mutex = Mutex()
    private var nextPoll = System.currentTimeMillis()
    private var notBefore = 0L
    override val id = "graph_extraction"
    private fun enabled() = memoryModule.isEnabled && memoryModule.lifecycle.value == ModuleLifecycle.STARTED
    override val nextRunTime: Long
        get() = if (!enabled()) Long.MAX_VALUE else maxOf(notBefore,
            if (memoryModule.graphPendingCount >= memoryModule.config.graphExtractionThreshold) System.currentTimeMillis() else nextPoll)

    override suspend fun execute() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!enabled()) return@withContext
            val now = System.currentTimeMillis()
            nextPoll = now + POLL_MS
            notBefore = nextPoll
            graphDao.enqueueGraphSources(MAX_MEMORIES_PER_RUN)
            val batch = graphDao.pendingGraphJobs(now, MAX_MEMORIES_PER_RUN)
            if (batch.isEmpty()) {
                memoryModule.resetGraphCount()
                return@withContext
            }
            try {
                val sources = batch.map { requireNotNull(memoryDao.getById(it.sourceMessageId!!)) }
                require(sources.none { memoryDao.recallBlocked(it.id) }) { "graph_source_redacted" }
                require(sources.all { it.content.length in 1..400 }) { "graph_source_limit" }
                val model = modelSelector.select(setOf(ModelCapability.CHAT)) ?: return@withContext
                val input = sources.mapIndexed { index, source -> "${index + 1}. ${source.content}" }.joinToString("\n")
                val raw = withTimeout(30_000) { model.chatSync(input, GraphExtractionBatch.PROMPT) }
                val parsed = GraphExtractionBatch.decode(raw, sources, now)
                if (!enabled()) return@withContext
                val committed = graphDao.commitGraphBatch(batch, sources, parsed.entities, parsed.attributes, parsed.relationships, now)
                if (committed) memoryModule.resetGraphCount()
                nextPoll = System.currentTimeMillis() + 60_000
                notBefore = nextPoll
                traceManager.record("mem_graph", NodeType.SYSTEM_EVENT, "memory",
                    "graph_batch: committed=$committed sources=${sources.size} attributes=${parsed.attributes.size} relationships=${parsed.relationships.size}")
            } catch (_: TimeoutCancellationException) {
                fail(batch, "timeout")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(batch, error.javaClass.simpleName)
            }
        }
    }

    private suspend fun fail(batch: List<MemoryExtractionJobEntity>, reason: String) {
        val now = System.currentTimeMillis()
        batch.forEach { job ->
            val delay = minOf(3_600_000L, POLL_MS * (1L shl job.attempts.coerceIn(0, 4)))
            jobs.markFailed(job.id, reason, MemoryExtractionJobEntity.MAX_ATTEMPTS, now + delay, now)
        }
        traceManager.record("mem_graph", NodeType.ERROR, "memory", "graph_batch_failed: count=${batch.size} reason=$reason")
    }
}
