package com.zero.v2.framework.chat

import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.data.dao.ConversationDao
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 会话滚动摘要器 — N6.3 / N6.6 世代压缩版（设计 §九 9.7）。
 *
 * 触发模型变更：不再每轮检查——仅世代切换时由 ChatBotModule 调用（切换天然稀疏：
 * 每涨 ~25% 预算才一次），摘要（system 内）随之稀疏变化 → 前缀缓存长周期稳定。
 *
 * 增量水印协议（X13：消息 id）不变：summary_upto=已压缩至的消息 id；
 * 本轮区间=watermark 起至新世代锚点，与旧摘要滚动合并。
 *
 * 模式先例：HistoryCompressor。§7.6 降级：LLM 失败 → trace 后跳过，不重试不阻塞。
 * X12：ConversationDao（core:data）直用。
 */
@Singleton
class ConversationSummarizer @Inject constructor(
    private val conversationDao: ConversationDao,
    private val modelSelector: ModelSelector,
    private val traceManager: AgentTraceManager
) {

    companion object {
        /** 单条消息入摘要调用的截断（控制压缩调用自身成本） */
        private const val PER_MSG_CHARS = 300
        /** 摘要调用输入侧总截断 */
        private const val REGION_MAX_CHARS = 6000
        private const val TRACE_ID = "ctx_compress"
    }

    /** 读摘要（ChatBotModule 注入 system 用；失败静默 null） */
    suspend fun getSummary(conversationId: String): String? = withContext(Dispatchers.IO) {
        try {
            conversationDao.getSummaryRow(conversationId)?.summary?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 世代切换时压缩被置换区间（异步调用，永不抛出）。
     * @param newAnchorMessageId 新世代锚点（切片首条消息 id）
     */
    suspend fun compressIfNeeded(
        conversationId: String,
        fullMessages: List<ChatMessage>,
        newAnchorMessageId: String?,
        summaryMaxChars: Int,
    ) = withContext(Dispatchers.IO) {
        try {
            doCompress(conversationId, fullMessages, newAnchorMessageId, summaryMaxChars)
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, "chatbot",
                "ctx_summarize_failed: ${e.message?.take(60)}")
        }
    }

    private suspend fun doCompress(
        conversationId: String,
        fullMessages: List<ChatMessage>,
        newAnchorMessageId: String?,
        summaryMaxChars: Int,
    ) {
        if (newAnchorMessageId.isNullOrBlank()) return

        val row = conversationDao.getSummaryRow(conversationId)
        val oldSummary = row?.summary
        val watermarkId = row?.summaryUpto

        val anchorIdx = fullMessages.indexOfFirst { it.id == newAnchorMessageId }
        if (anchorIdx <= 0) return
        val fromIdx = watermarkId
            ?.let { w -> fullMessages.indexOfFirst { it.id == w } }
            ?.takeIf { it >= 0 } ?: 0
        if (fromIdx >= anchorIdx) return  // 无新增区间

        // 区间文本（TOOL 结果带名标注；跳过空消息；输入侧截断）
        val region = fullMessages.subList(fromIdx, anchorIdx)
        val numbered = region.mapIndexedNotNull { i, m ->
            val text = (m.content ?: "").trim()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val label = if (m.role == com.zero.v2.core.common.MessageRole.TOOL && m.toolCallName != null)
                "[工具${m.toolCallName}]" else "[${m.role}]"
            "${i + 1}. $label ${text.take(PER_MSG_CHARS)}"
        }.joinToString("\n").take(REGION_MAX_CHARS)

        if (numbered.isBlank()) {
            // 空区间（全空消息）——仅推水印防反复进入
            conversationDao.updateSummary(conversationId, oldSummary ?: "", newAnchorMessageId)
            return
        }

        val model = try {
            modelSelector.select(setOf(ModelCapability.CHAT))
        } catch (_: Exception) {
            null
        }
        if (model == null) {
            traceManager.record(TRACE_ID, NodeType.SYSTEM_EVENT, "chatbot",
                "ctx_summarize_skipped: no_model")
            return
        }

        val merged = try {
            val contextPart = if (oldSummary.isNullOrBlank()) "" else "已有摘要：\n$oldSummary\n\n"
            model.chatSync(
                "${contextPart}以下为会话新增内容，请与已有摘要滚动合并为一段第三人称摘要" +
                    "（保留人名/事项/决定/时序，不超过 $summaryMaxChars 字，只输出摘要）：\n$numbered",
                "你是会话摘要引擎，只输出摘要本身。"
            ).trim().take(summaryMaxChars)
        } catch (e: Exception) {
            traceManager.record(TRACE_ID, NodeType.ERROR, "chatbot",
                "ctx_summarize_failed: ${e.message?.take(60)}")
            return  // §7.6：仅窗口运行
        }
        if (merged.isBlank()) return

        conversationDao.updateSummary(conversationId, merged, newAnchorMessageId)
        traceManager.record(TRACE_ID, NodeType.COMPRESSION, "chatbot",
            "ctx_summarized: chars=${merged.length} from=$fromIdx to=$anchorIdx " +
                "watermark=${newAnchorMessageId.take(8)}")
    }
}
