package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.dao.MemoryDao
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.embedding.VectorIndexRuntime
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 历史压缩器 — Step 4.7（对标 Alife L0-L8 多级压缩）。
 *
 * 当记忆总量超过阈值（§3.4 compressionThreshold，默认 100）时：
 * 取最老的一批低层 ACTIVE 记忆 → LLM 压缩为一段高一层（level+1）摘要 → 原件归档。
 * 检索池因此只保留摘要 + 新记忆，总量受控；归档原件可追溯。
 *
 * 触发: 4.8 MemoryReflectionJob 周期调用 compressIfNeeded()。
 * 成本: 每次压缩一次 chatSync（~1500 token），超阈值才触发。
 */
@Singleton
class HistoryCompressor @Inject constructor(
    private val memoryDao: MemoryDao,
    private val modelSelector: ModelSelector,
    private val vectorIndex: VectorIndexRuntime,
    private val traceManager: AgentTraceManager
) {
    private val commitMutex = Mutex()

    companion object {
        /** §3.4 默认阈值（5.2 接入 MemoryModuleConfig） */
        const val DEFAULT_THRESHOLD = 100
        /** 每次压缩的最老条数 */
        const val BATCH_SIZE = 20
        /** 压缩层级上限（防摘要无限嵌套；Alife 为 L8，工程上 L4 足够） */
        const val MAX_LEVEL = 4
    }

    /**
     * 检查并压缩一轮。
     * @return 生成的摘要条数（0 = 未触发 / 条件不满足 / LLM 不可用）
     */
    suspend fun compressIfNeeded(threshold: Int = DEFAULT_THRESHOLD): Int = withContext(Dispatchers.IO) {
        val total = try { memoryDao.count() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { 0 }
        if (total < threshold) return@withContext 0

        val batch = try { memoryDao.getOldestForCompression(MAX_LEVEL, BATCH_SIZE).filter { it.content.length <= 400 } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        if (batch.size < BATCH_SIZE / 2) return@withContext 0  // 可压存量不足

        val model = try { modelSelector.select(setOf(ModelCapability.CHAT)) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }
        if (model == null) {
            traceManager.record("mem_compress", NodeType.SYSTEM_EVENT, "memory",
                "compress_skipped: no_model total=$total")
            return@withContext 0
        }

        val summary = try {
            val numbered = batch.mapIndexed { i, m -> "${i + 1}. ${m.content}" }.joinToString("\n")
            model.chatSync(
                "将以下 ${batch.size} 条用户记忆压缩为一段简洁的第三人称摘要（保留人名/地点/偏好/重要事件与时序，不超过 200 字，只输出摘要）：\n$numbered",
                "你是记忆压缩引擎，只输出摘要本身。"
            ).trim()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (e: Exception) {
            traceManager.record("mem_compress", NodeType.ERROR, "memory",
                "compress_failed: ${e.message?.take(50)}")
            return@withContext 0
        }
        if (summary.isBlank() || summary.length > 400) return@withContext 0

        // 摘要入库（level+1，INSIGHT 类型），原件归档
        val newLevel = (batch.maxOf { it.level } + 1).coerceAtMost(MAX_LEVEL)
        val summaryId = UUID.randomUUID().toString()
        val committed = commitMutex.withLock { memoryDao.commitCompression(batch, MemoryEntity(
            id = summaryId,
            content = "[压缩 L$newLevel] $summary",
            level = newLevel,
            importance = 0.6f,
            tier = 1,
            type = MemoryType.INSIGHT,
            status = MemoryStatus.ACTIVE,
            confidence = 0.5f,
            origin = "MODEL_SUMMARY"
        )) }
        if (!committed) return@withContext 0

        // The worker re-reads the committed source and applies the Memory input contract.
        try {
            vectorIndex.build(VectorDomain.MEMORY)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            traceManager.record("mem_compress", NodeType.ERROR, "memory",
                "vector_candidate_failed: ${failure.javaClass.simpleName}")
        }

        traceManager.record("mem_compress", NodeType.COMPRESSION, "memory",
            "compress_done: total=$total archived=${batch.size} level=$newLevel")
        1
    }

}
