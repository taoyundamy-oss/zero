package com.zero.v2.app.ui.settings

import com.zero.v2.core.moduleapi.RetrievalDomainStatus
import com.zero.v2.core.moduleapi.StatusFact

/** UI renders the same owner observations as zero_status; missing counts remain visibly unknown. */
object RetrievalStatusPresentation {
    fun text(status: RetrievalDomainStatus?): String {
        if (status == null || status.freshness == StatusFact.UNKNOWN) return "状态未知"
        val version = status.activeVersion
        val index = when {
            version == null -> "尚无活动索引"
            status.activeSpaceMatches == false -> "索引与当前模型不兼容"
            status.activeSpaceMatches == null -> "索引兼容性未知"
            else -> "索引 ${version.take(12)}"
        }
        return buildString {
            if (status.freshness == StatusFact.STALE) append("状态已过期 · ")
            append(index)
            append(" · 待索引 ${status.backlog ?: "未知"}")
            status.candidateState?.let { append(" · 候选 $it") }
        }
    }
}
