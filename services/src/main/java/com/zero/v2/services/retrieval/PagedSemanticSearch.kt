package com.zero.v2.services.retrieval

import kotlinx.coroutines.yield
import java.util.PriorityQueue

/** Scan every eligible vector with a bounded page and top-K heap; recency never removes semantic candidates. */
object PagedSemanticSearch {
    suspend fun <T> search(
        limit: Int,
        page: suspend (String, Int) -> List<T>,
        id: (T) -> String,
        score: (T) -> Float?,
    ): List<T> {
        val top = PriorityQueue<Pair<Float, T>>(compareBy { it.first })
        var after = ""
        do {
            yield()
            val batch = page(after, PAGE_SIZE)
            for (item in batch) {
                offer(top, item, score(item), limit)
            }
            val next = batch.lastOrNull()?.let(id) ?: break
            check(next > after) { "Semantic page cursor did not advance" }
            after = next
        } while (batch.size == PAGE_SIZE)
        return top.sortedByDescending { it.first }.map { it.second }
    }

    private fun <T> offer(top: PriorityQueue<Pair<Float, T>>, item: T, score: Float?, limit: Int) {
        if (score == null || !score.isFinite()) return
        top.add(score to item)
        if (top.size > limit.coerceAtLeast(1)) top.remove()
    }

    private const val PAGE_SIZE = 128
}
