package com.zero.v2.services.embedding

import com.zero.v2.core.data.dao.VectorIndexDao
import com.zero.v2.core.data.entity.VectorEntryEntity
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.yield

/** Exact content identity, independent of regenerated database IDs. No fuzzy label reassignment. */
object VectorRecallSources {
    fun digest(text: String, title: String = "", heading: String = ""): String {
        val hash = MessageDigest.getInstance("SHA-256")
        listOf(text, title, heading).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    internal suspend fun resolve(
        dao: VectorIndexDao, version: String, data: VectorRecallDataset,
    ): Map<String, String> {
        val labels = data.samples.flatMap { it.relevant + it.forbidden }.toSet()
        if (data.sourceDigests.isEmpty()) return labels.associateWith { it }
        val wanted = data.sourceDigests.values.toSet()
        val found = mutableMapOf<String, String>()
        var after = ""
        while (true) {
            yield()
            val rows = dao.candidatePage(version, after, SOURCE_PAGE_SIZE)
            if (rows.isEmpty()) break
            rows.forEach { row -> match(row, wanted, found) }
            after = rows.last().sourceId
        }
        check(found.keys == wanted) { "vector_evaluation_source_missing" }
        return data.sourceDigests.mapValues { (_, digest) -> found.getValue(digest) }
    }

    private fun match(row: VectorEntryEntity, wanted: Set<String>, found: MutableMap<String, String>) {
        val digest = digest(row.sourceText, row.sourceTitle, row.sourceHeading)
        if (digest in wanted) {
            check(found.put(digest, row.sourceId) == null) { "vector_evaluation_source_ambiguous" }
        }
    }

    private const val SOURCE_PAGE_SIZE = 128
}
