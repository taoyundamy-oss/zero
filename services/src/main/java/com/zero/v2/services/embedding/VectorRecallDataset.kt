package com.zero.v2.services.embedding

import java.nio.ByteBuffer
import java.security.MessageDigest

private const val MAX_LABEL_LENGTH = 128
private const val MAX_SAMPLES = 64
private const val MAX_TOP_K = 20
private const val MAX_QUERY_LENGTH = 1000
private const val RECALL_FLOOR = 0.9

/** Human-labelled queries against real source IDs. Never derive queries from their answer text. */
data class VectorRecallDataset(
    val version: String,
    val domain: VectorDomain,
    val samples: List<Sample>,
    val limit: Int = 5,
    val minimumRecall: Double = 0.9,
    val sourceDigests: Map<String, String> = emptyMap(),
) {
    enum class Kind { SOURCE, NEGATION, CORRECTION }
    data class Sample(
        val id: String,
        val query: String,
        val kind: Kind,
        val relevant: Set<String>,
        val forbidden: Set<String> = emptySet(),
    )

    internal fun snapshot(): VectorRecallDataset = copy(sourceDigests = sourceDigests.toMap(), samples = samples.map {
        it.copy(relevant = it.relevant.toSet(), forbidden = it.forbidden.toSet())
    }).also { data ->
        require(data.version.isNotBlank() && data.version.length <= MAX_LABEL_LENGTH)
        require(data.samples.size in Kind.entries.size..MAX_SAMPLES && data.limit in 1..MAX_TOP_K)
        require(data.minimumRecall.isFinite() && data.minimumRecall in RECALL_FLOOR..1.0)
        require(data.samples.map { it.id }.distinct().size == data.samples.size)
        require(data.samples.map { it.kind }.toSet() == Kind.entries.toSet())
        val labels = data.samples.flatMap { it.relevant + it.forbidden }.toSet()
        require(data.sourceDigests.isEmpty() || data.sourceDigests.keys == labels)
        require(data.sourceDigests.values.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(data.sourceDigests.values.distinct().size == data.sourceDigests.size)
        data.samples.forEach { sample ->
            require(sample.id.isNotBlank() && sample.id.length <= MAX_LABEL_LENGTH)
            require(sample.query.isNotBlank() && sample.query.length <= MAX_QUERY_LENGTH)
            require(sample.relevant.size in 1..data.limit && sample.forbidden.size <= MAX_TOP_K)
            require((sample.relevant + sample.forbidden).all { it.isNotBlank() && it.length <= MAX_LABEL_LENGTH })
            require(sample.relevant.intersect(sample.forbidden).isEmpty())
            require(sample.kind == Kind.SOURCE || sample.forbidden.isNotEmpty())
        }
    }

    internal fun digest(): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            hash.update(bytes)
        }
        listOf(version, domain.key, limit.toString(), minimumRecall.toString()).forEach(::add)
        samples.sortedBy { it.id }.forEach {
            listOf(it.id, it.query, it.kind.name, it.relevant.size.toString()).forEach(::add)
            it.relevant.sorted().forEach(::add)
            add(it.forbidden.size.toString())
            it.forbidden.sorted().forEach(::add)
        }
        sourceDigests.toSortedMap().forEach { (label, digest) -> add(label); add(digest) }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}

