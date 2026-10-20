package com.zero.v2.core.data.dao

import androidx.sqlite.db.SimpleSQLiteQuery

/** Bound legacy term reads share one SQLite snapshot; statements, never facts, are cached. */
internal object LexicalGroupQuery {
    private val memoryColumns = listOf("id", "content", "embedding", "level", "importance",
        "source_conversation_id", "keywords", "tier", "type", "status", "confidence",
        "reinforcement_count", "created_at", "access_count", "last_accessed_at", "embed_model")
    private val knowledgeColumns = listOf("id", "doc_id", "seq", "heading_path", "text",
        "char_len", "embedding", "embed_model", "updated_at")
    private val memorySql = (1..8).map { count ->
        statement("memories", memoryColumns, count,
            "SELECT id FROM memories WHERE status = 'ACTIVE' " +
                "AND (instr(content, ?) > 0 OR instr(COALESCE(keywords, ''), ?) > 0) " +
                "ORDER BY importance DESC LIMIT ?")
    }
    private val knowledgeSql = (1..8).map { count ->
        statement("kb_chunks", knowledgeColumns, count,
            "SELECT id FROM kb_chunks WHERE text LIKE '%' || ? || '%' LIMIT ?")
    }

    fun memory(terms: List<String>, limit: Int): SimpleSQLiteQuery {
        require(terms.size in 1..8 && limit in 1..100)
        val args = Array<Any>(terms.size * 3) { index ->
            if (index % 3 == 2) limit else terms[index / 3]
        }
        return SimpleSQLiteQuery(memorySql[terms.size - 1], args)
    }

    fun knowledge(terms: List<String>, limit: Int): SimpleSQLiteQuery {
        require(terms.size in 1..8 && limit in 1..300)
        val args = Array<Any>(terms.size * 2) { index ->
            if (index % 2 == 1) limit else terms[index / 2]
        }
        return SimpleSQLiteQuery(knowledgeSql[terms.size - 1], args)
    }

    private fun statement(table: String, columns: List<String>, count: Int, branch: String): String {
        // Explicit names preserve migrated column layouts; SELECT entity.* would be position dependent.
        val materialized = columns.joinToString { "entity.$it" }
        val matches = (0 until count).joinToString(" UNION ALL ") { index ->
            "SELECT $index AS term_index, id AS match_id FROM ($branch)"
        }
        // Payload is read once per ID; the separate ID stream retains every branch's original order.
        val emptyPayload = columns.joinToString { "NULL AS $it" }
        return "WITH matches AS ($matches) " +
            "SELECT -1 AS term_index, entity.id AS match_id, $materialized FROM $table AS entity " +
            "WHERE entity.id IN (SELECT match_id FROM matches) UNION ALL " +
            "SELECT term_index, match_id, $emptyPayload FROM matches"
    }
}
