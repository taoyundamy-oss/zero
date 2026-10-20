package com.zero.v2.core.data.entity

/** Read-only transaction snapshot; the current projection is recomputed, never trusted from the cache. */
data class ClassifiedMemorySnapshot(
    val row: MemoryDerivationProjection.Snapshot,
    val view: MemoryDerivationViewEntity,
    val replaced: Boolean,
    val confirmation: MemoryEvidenceSnapshot? = null,
)
