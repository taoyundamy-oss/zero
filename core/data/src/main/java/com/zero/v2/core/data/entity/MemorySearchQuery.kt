package com.zero.v2.core.data.entity

import java.util.Locale

/** Literal lexical terms, never semantic certification or a generated SQL fragment. */
object MemorySearchQuery {
    data class Plan(val sql: String, val args: List<Any>)
    fun sources(query: String, limit: Int): Plan {
        require(limit in 1..8)
        val words = terms(query)
        val matching = words.joinToString(" AND ") { "instr(lower(s.body), ?) > 0" }.ifEmpty { "1" }
        return Plan("SELECT s.* FROM chronicle_sources s WHERE $matching AND NOT EXISTS(" +
            "SELECT 1 FROM chronicle_deletions d WHERE ((d.kind=s.kind OR (d.kind='transcript' AND s.kind='message')) " +
            "AND d.anchorId=s.anchorId) OR (d.kind='conversation' AND d.anchorId=s.conversationId)) " +
            "AND NOT EXISTS(SELECT 1 FROM memory_deletions m WHERE m.id=s.parentMemoryId) " +
            "ORDER BY s.capturedAt DESC,s.id LIMIT ?", words + limit)
    }

    fun slots(scope: String, query: String): Plan? {
        require(scope.isNotBlank() && scope.length <= 100)
        val words = terms(query)
        if (words.isEmpty()) return null
        // RTRIM removes all non-slash key characters up to the final slash, yielding its prefix.
        val subject = "trim(substr(lower(claimKey),1,length(rtrim(lower(claimKey),replace(lower(claimKey),'/','')))-1))"
        val anchored = words.filter { it.length >= 2 }
        val subjectTerms = anchored.joinToString(" OR ") { "instr($subject, ?) > 0" }.ifEmpty { "0" }
        val allKeyTerms = words.joinToString(" AND ") { "instr(lower(claimKey), ?) > 0" }
        val clause = "(instr(claimKey,'/')>1 AND (instr(?, $subject)>0 OR $subjectTerms)) " +
            "OR (instr(claimKey,'/')=0 AND $allKeyTerms)"
        return Plan("SELECT slotId,MIN(claimKey) AS claimKey FROM memory_derivations " +
            "WHERE scope=? AND ($clause) GROUP BY slotId ORDER BY slotId LIMIT 17",
            listOf(scope, query.lowercase(Locale.ROOT)) + anchored + words)
    }
    fun terms(query: String): List<String> {
        require(query.length <= 200) { "query_too_long" }
        val words = query.lowercase(Locale.ROOT).split(Regex("[\\p{Z}\\s,，、;；]+"))
            .filter { it.isNotBlank() }.distinct()
        require(words.size <= 8) { "query_too_many_terms" }
        require(query.isBlank() || words.isNotEmpty()) { "query_has_no_terms" }
        return words
    }

    /** Structured keys require a subject anchor; matching an attribute alone is insufficient. */
    fun matchesSubject(query: String, claimKey: String): Boolean {
        val words = terms(query)
        if (words.isEmpty()) return false
        val key = claimKey.lowercase(Locale.ROOT)
        val subject = key.substringBeforeLast('/', "").trim()
        return if (subject.isNotEmpty()) query.lowercase(Locale.ROOT).contains(subject) ||
            words.any { it.length >= 2 && subject.contains(it) }
        else words.all { key.contains(it) }
    }
}

data class MemorySlotKey(val slotId: String, val claimKey: String)

data class MemoryConflictDiagnostic(val slotId: String, val claimKey: String, val scope: String,
    val version: String, val eligibleCount: Int, val candidates: List<MemoryDerivationProjection.Snapshot>,
    val truncated: Boolean)
