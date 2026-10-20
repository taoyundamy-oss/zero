package com.zero.v2.services.retrieval

import java.time.LocalDate

/** Reserve at most half of the existing rerank window for explicitly dated recent candidates. */
object RecentRecallWindow {
    fun requested(query: String): Boolean = QUERY.matches(query.trim().trimEnd('？', '?', '。'))

    fun limit(query: String, normal: Int): Int = if (requested(query)) RetrievalKit.DEFAULT_CANDIDATE_CAP else normal

    fun date(text: String): Long? = DATE.findAll(text).mapNotNull { match ->
        runCatching { LocalDate.parse(match.value).toEpochDay() }.getOrNull()
    }.maxOrNull()

    fun <T> arrange(query: String, items: List<T>, id: (T) -> String, date: (T) -> Long?): List<T> {
        if (!requested(query)) return items
        val today = LocalDate.now().toEpochDay()
        val recent = items.mapNotNull { item -> date(item)?.takeIf { it <= today }?.let { item to it } }
            .sortedByDescending { it.second }.take(BoundedSemanticReranker.MAX_CANDIDATES / 2).map { it.first }
        return if (recent.isEmpty()) items
        else (recent + items.take(BoundedSemanticReranker.MAX_CANDIDATES / 2) + items).distinctBy(id)
    }

    private val QUERY = Regex("(?:(?:我|我们)(?:最近|近期)|(?:最近|近期)(?:我|我们))" +
        "(?:在做什么|做了什么|做过什么|有什么活动)")
    private val DATE = Regex("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])")
}
