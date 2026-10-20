package com.zero.v2.core.moduleapi

/** Observation age is independent of whether the underlying capability is enabled. */
object StatusProjection {
    fun freshness(observedAt: Long, now: Long, ttl: Long): StatusFact = when {
        observedAt <= 0L || observedAt > now -> StatusFact.UNKNOWN
        now - observedAt > ttl -> StatusFact.STALE
        else -> StatusFact.LIVE
    }

    fun page(
        snapshot: SystemSnapshot, sections: Set<String>, facts: Map<String, Any>,
        unknown: List<String>, stale: List<String>, limit: Int, cursor: String?,
    ): ZeroStatusResult {
        // Length framing binds even unknown section names without separator ambiguity.
        val selection = sections.sorted().joinToString("") { "${it.length}:$it" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(selection.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val parts = cursor?.split(':')
        val offset = parts?.getOrNull(1)?.toIntOrNull() ?: 0
        val rows = facts.flatMap { (key, value) ->
            if (value is List<*> && value.isNotEmpty()) value.map { key to it } else listOf(key to value)
        }
        if (parts != null && (parts.size != 3 || parts[0] != snapshot.revision.toString() ||
                parts[1].toIntOrNull() == null || parts[2] != digest || offset !in rows.indices)) {
            return ZeroStatusResult(snapshot.revision, snapshot.capturedAt, emptyMap(),
                listOf("cursor_expired"), emptyList(), false, null)
        }
        val page = rows.drop(offset).take(limit.coerceIn(1, 100))
        val paged = linkedMapOf<String, Any>()
        page.groupBy { it.first }.forEach { (key, values) ->
            val original = facts.getValue(key)
            paged[key] = when {
                original is List<*> && original.isEmpty() -> emptyList<Any>()
                original is List<*> -> values.map { it.second }
                else -> values.first().second!!
            }
        }
        val next = offset + page.size
        return ZeroStatusResult(snapshot.revision, snapshot.capturedAt, paged, unknown.distinct(), stale.distinct(),
            next < rows.size, if (next < rows.size) "${snapshot.revision}:$next:$digest" else null)
    }
}
