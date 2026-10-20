package com.zero.v2.core.data.entity

/** Bounded, single-transaction input for a local projection; no new persistent table. */
data class MemoryChronicleSnapshot(
    val entries: List<MemoryEvidenceSnapshot>,
    val dependencies: List<MemorySourceEntity>,
)
