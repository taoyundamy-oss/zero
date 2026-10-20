package com.zero.v2.services.embedding

/** Debug-only access to the unchanged fixed benchmark for cross-module tool acceptance. */
object RecallAcceptanceFixture {
    val sources get() = VectorRecallBenchmark.sources.toList()
    fun dataset(domain: VectorDomain) = VectorRecallBenchmark.dataset(domain)
}
