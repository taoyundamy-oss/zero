package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity

/** Read-side hygiene only; never edits, merges or certifies stored source records. */
internal object MemoryRecallCandidates {
    fun key(memory: MemoryEntity): String = memory.content.trim().trimEnd('。', '.', ' ', '\n', '\r')

    fun select(query: String, rows: List<MemoryEntity>): List<MemoryEntity> {
        val history = HISTORY.containsMatchIn(query)
        return rows.filter { (history || !unansweredLegacyQuestion(it)) &&
            com.zero.v2.services.retrieval.CurrentRecallPolicy.eligible(query, it.content) }.distinctBy(::key)
    }

    private fun unansweredLegacyQuestion(memory: MemoryEntity): Boolean {
        if (memory.type != MemoryType.EVENT || memory.sourceConversationId != null) return false
        val body = memory.content.removeSuffix(LEGACY_GUIDANCE).trim()
            .trimEnd('？', '?', '。', '.').replace(PREFIX, "").replace(SUFFIX, "")
        return QUESTION.matches(body)
    }

    private const val LEGACY_GUIDANCE = "\n(注意消息来源和意图；调用工具后等待结果；保持回复简洁；禁止编造结果)"
    private val PREFIX = Regex("^(?:嗯[，,、]?)?(?:你可知道|你知道|请告诉我)?")
    private val SUFFIX = Regex("[，,](?:不妨整理一下|请整理一下)$")
    private val QUESTION = Regex("(?:我|我们)(?:最近|现在|今天|这周|这几天)?" +
        "(?:在做什么|做了什么|有什么(?:日程|安排|计划)|的(?:喜好|偏好|约定)(?:是什么|有哪些))")
    private val HISTORY = Regex("问过|问了|提问|问题记录|提过.*问题")
}
