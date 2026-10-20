package com.zero.v2.core.data.entity

import com.zero.v2.core.common.MemoryStatus

/** Pure, scope-local projection. It attests structural binding only, not semantic correctness. */
object MemoryDerivationProjection {
    data class Snapshot(val entry: MemoryDerivationEntity, val source: ChronicleSourceEntity?,
        val memory: MemoryEntity?, val blocked: Boolean = false, val conflict: Boolean = false)

    fun rebuild(slotId: String, scope: String, rows: List<Snapshot>, now: Long): MemoryDerivationViewEntity {
        require(rows.all { it.entry.slotId == slotId && it.entry.scope == scope })
        require(rows.map { it.entry.id }.distinct().size == rows.size)
        val eligible = rows.filter { row ->
            val d = row.entry; val m = row.memory; val s = row.source
            d.state == "APPLIED" && s != null && m != null && !row.blocked &&
                runCatching { d.validate(); s.validate() }.isSuccess &&
                m.status == MemoryStatus.ACTIVE && d.boundTo(s) && d.matches(m, s) &&
                (d.validFrom == null || now >= d.validFrom) && (d.validUntil == null || now < d.validUntil)
        }.sortedWith(compareBy({ it.source!!.recordedAt }, { it.entry.id }))
        val conflict = eligible.any { it.conflict } || eligible.map { it.entry.meaning() }.distinct().size > 1
        val state = when {
            conflict -> "CONFLICT"
            eligible.isNotEmpty() -> "CANDIDATE"
            rows.any { it.entry.state == "PENDING" && !it.blocked && it.source != null } -> "PENDING"
            rows.isNotEmpty() -> "INACTIVE"
            else -> "EMPTY"
        }
        val selected = eligible.firstOrNull()?.entry?.id?.takeUnless { conflict }
        // Conflict is represented by projection state. Persisting its audit must not churn an already-isolated view.
        val version = MemoryDerivationEntity.digest(listOf(slotId, state) + rows.sortedBy { it.entry.id }.map {
            listOf(it.entry.id, it.entry.state, it.source?.id.orEmpty(), it.memory?.let(MemoryConflictEntity::version).orEmpty(),
                it.memory?.status?.name.orEmpty(), it.blocked.toString(),
                (it.entry.id in eligible.map { e -> e.entry.id }).toString()).joinToString(":")
        })
        return MemoryDerivationViewEntity(slotId, scope, state, selected, eligible.size, version, now)
    }
}
