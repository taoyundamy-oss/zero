package com.zero.v2.core.data.knowledge

/** Merge key-ordered scoped queries without exceeding SQLite bind or page-memory budgets. */
object ScopedDocumentQuery {
    private const val DOCUMENTS_PER_QUERY = 500

    suspend fun <T> page(
        documents: List<String>, limit: Int, key: (T) -> String,
        query: suspend (List<String>) -> List<T>,
    ): List<T> {
        var rows = emptyList<T>()
        for (batch in documents.chunked(DOCUMENTS_PER_QUERY)) {
            rows = (rows + query(batch)).sortedBy(key).distinctBy(key).take(limit)
        }
        return rows
    }
}
