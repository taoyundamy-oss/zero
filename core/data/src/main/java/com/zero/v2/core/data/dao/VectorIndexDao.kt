package com.zero.v2.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zero.v2.core.data.entity.VectorActiveEntity
import com.zero.v2.core.data.entity.VectorEntryEntity
import com.zero.v2.core.data.entity.VectorSourceRow
import com.zero.v2.core.data.entity.VectorValidationEntity
import com.zero.v2.core.data.entity.VectorVersionEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private const val LIVE_SOURCE =
    "((v.domain = 'memory' AND EXISTS (SELECT 1 FROM memories m WHERE m.id = e.sourceId " +
        "AND m.status = 'ACTIVE' AND m.content = e.sourceText AND e.sourceTitle = '' AND e.sourceHeading = '' " +
        "AND " + CHRONICLE_LIVE_M + ")) OR " +
        "(v.domain = 'knowledge' AND EXISTS (SELECT 1 FROM kb_chunks c JOIN kb_docs d ON d.id = c.doc_id " +
        "WHERE c.id = e.sourceId AND c.text = e.sourceText AND d.title = e.sourceTitle AND c.heading_path = e.sourceHeading)))"

/** Transactional storage contract. Owner workers additionally serialize writes with their Mutex. */
@Dao
abstract class VectorIndexDao {
    @Query("SELECT * FROM vector_versions WHERE id = :id")
    abstract suspend fun version(id: String): VectorVersionEntity?

    @Query("SELECT v.* FROM vector_versions v JOIN vector_active a ON a.versionId = v.id WHERE a.domain = :domain")
    abstract suspend fun active(domain: String): VectorVersionEntity?

    @Query("SELECT * FROM vector_versions WHERE domain = :domain ORDER BY createdAt DESC, id")
    abstract suspend fun versions(domain: String): List<VectorVersionEntity>

    @Query("SELECT * FROM vector_validation WHERE versionId = :id")
    abstract suspend fun validation(id: String): VectorValidationEntity?

    @Query("SELECT * FROM vector_entries WHERE versionId = :id AND sourceId > :after ORDER BY sourceId LIMIT :limit")
    abstract suspend fun candidatePage(id: String, after: String, limit: Int): List<VectorEntryEntity>

    @Query("SELECT COUNT(*) FROM vector_entries WHERE versionId = :id")
    abstract suspend fun countEntries(id: String): Int

    @Query("SELECT id, content AS text, '' AS title, '' AS heading FROM memories " +
        "WHERE :domain = 'memory' AND status = 'ACTIVE' AND id > :after AND " + CHRONICLE_LIVE + " " +
        "UNION ALL SELECT c.id, c.text, d.title, c.heading_path FROM kb_chunks c JOIN kb_docs d ON d.id = c.doc_id " +
        "WHERE :domain = 'knowledge' AND c.id > :after ORDER BY id LIMIT :limit")
    abstract suspend fun sourcePage(domain: String, after: String, limit: Int): List<VectorSourceRow>

    @Query("SELECT (SELECT COUNT(*) FROM memories WHERE :domain = 'memory' AND status = 'ACTIVE' AND " + CHRONICLE_LIVE + ") + " +
        "(SELECT COUNT(*) FROM kb_chunks WHERE :domain = 'knowledge')")
    abstract suspend fun sourceCount(domain: String): Int

    @Query("SELECT e.* FROM vector_entries e JOIN vector_versions v ON v.id = e.versionId " +
        "WHERE e.versionId = :id AND v.state IN ('PUBLISHED', 'RETIRED') AND e.sourceId > :after AND " +
        LIVE_SOURCE + " ORDER BY e.sourceId LIMIT :limit")
    abstract suspend fun publishedPage(id: String, after: String, limit: Int): List<VectorEntryEntity>

    @Query("SELECT e.* FROM vector_entries e JOIN vector_versions v ON v.id = e.versionId " +
        "JOIN kb_chunks c ON c.id = e.sourceId WHERE v.domain = 'knowledge' AND c.doc_id IN (:documents) " +
        "AND e.versionId = :id AND v.state IN ('PUBLISHED', 'RETIRED') AND e.sourceId > :after AND " +
        LIVE_SOURCE + " ORDER BY e.sourceId LIMIT :limit")
    abstract suspend fun scopedPublishedPage(
        id: String, after: String, documents: List<String>, limit: Int,
    ): List<VectorEntryEntity>

    @Query("SELECT e.* FROM vector_entries e JOIN vector_versions v ON v.id = e.versionId " +
        "WHERE e.versionId = :id AND v.state IN ('PUBLISHED', 'RETIRED') AND e.sourceId = :sourceId AND " + LIVE_SOURCE)
    abstract suspend fun publishedEntry(id: String, sourceId: String): VectorEntryEntity?

    @Query("SELECT COUNT(*) FROM vector_entries e JOIN vector_versions v ON v.id = e.versionId " +
        "WHERE e.versionId = :id AND " + LIVE_SOURCE)
    abstract suspend fun matchingCount(id: String): Int

    @Query("SELECT * FROM vector_entries WHERE versionId = :id AND sourceId IN (:sourceIds)")
    abstract suspend fun entriesFor(id: String, sourceIds: List<String>): List<VectorEntryEntity>

    @Query("SELECT COALESCE(SUM(length(CAST(sourceText AS BLOB)) + length(CAST(sourceTitle AS BLOB)) + " +
        "length(CAST(sourceHeading AS BLOB)) + length(vector)), 0) FROM vector_entries WHERE versionId = :id")
    abstract suspend fun storedBytes(id: String): Long

    @Query("DELETE FROM vector_versions WHERE id = :id AND state != 'PUBLISHED' " +
        "AND id NOT IN (SELECT versionId FROM vector_active)")
    abstract suspend fun removeUnpublished(id: String)

    @Insert protected abstract suspend fun insertVersion(value: VectorVersionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertEntries(values: List<VectorEntryEntity>)
    @Insert protected abstract suspend fun insertValidation(value: VectorValidationEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun setActive(value: VectorActiveEntity)
    @Query("UPDATE vector_versions SET state = :state, digest = :digest WHERE id = :id")
    protected abstract suspend fun setState(id: String, state: String, digest: String)

    @Transaction
    open suspend fun begin(value: VectorVersionEntity) {
        require(value.domain in setOf("memory", "knowledge"))
        require(value.id.isNotBlank() && value.space.isNotBlank() && value.dimensions in 1..8192)
        require(value.expectedCount in 0..100_000 && value.state == "BUILDING" && value.digest.isEmpty())
        check(active(value.domain)?.id == value.baseVersion) { "vector_base_changed" }
        check(sourceCount(value.domain) == value.expectedCount) { "vector_source_count_changed" }
        insertVersion(value)
    }

    @Transaction
    open suspend fun stage(id: String, values: List<VectorEntryEntity>) {
        val candidate = checkNotNull(version(id))
        check(candidate.state == "BUILDING") { "vector_candidate_frozen" }
        require(values.size in 1..100 && values.all { it.versionId == id && it.sourceId.isNotBlank() })
        require(values.map { it.sourceId }.distinct().size == values.size)
        values.forEach { validateVector(it.vector, candidate.dimensions) }
        insertEntries(values)
        check(matchingCount(id) == countEntries(id)) { "vector_source_changed" }
        check(storedBytes(id) <= 128L * 1024 * 1024) { "vector_candidate_byte_limit" }
        check(countEntries(id) <= candidate.expectedCount) { "vector_candidate_overflow" }
    }

    /** Freeze before external evaluation: reports cannot approve mutable candidates. */
    @Transaction
    open suspend fun seal(id: String): String {
        val candidate = checkNotNull(version(id))
        check(candidate.state == "BUILDING" || candidate.state == "VALIDATING")
        requireComplete(candidate)
        val digest = candidateDigest(candidate)
        setState(id, "VALIDATING", digest)
        return digest
    }

    @Transaction
    open suspend fun approve(report: VectorValidationEntity) {
        val candidate = checkNotNull(version(report.versionId))
        check(candidate.state == "VALIDATING" && candidate.digest == report.candidateDigest)
        require(report.datasetVersion.isNotBlank() && report.datasetDigest.matches(Regex("[a-f0-9]{64}")))
        require(report.expectedSamples > 0 && report.evaluatedSamples == report.expectedSamples)
        require(report.baselineRecall.isFinite() && report.baselineRecall in 0.0..1.0)
        require(report.minimumRecall.isFinite() && report.minimumRecall > 0.0 && report.minimumRecall <= 1.0)
        require(report.candidateRecall.isFinite() && report.candidateRecall in 0.0..1.0)
        require(report.candidateRecall >= report.minimumRecall && report.candidateRecall >= report.baselineRecall)
        require(report.report.isNotBlank() && report.report.length <= 65_536)
        candidate.baseVersion?.let { previousId ->
            val previous = checkNotNull(validation(previousId))
            require(report.datasetVersion == previous.datasetVersion && report.datasetDigest == previous.datasetDigest &&
                report.expectedSamples == previous.expectedSamples) { "vector_baseline_dataset_changed" }
            require(report.baselineRecall == previous.candidateRecall) { "vector_baseline_report_mismatch" }
        }
        requireComplete(candidate)
        check(candidateDigest(candidate) == candidate.digest) { "vector_candidate_corrupt" }
        insertValidation(report)
        setState(candidate.id, "READY", candidate.digest)
    }

    @Transaction
    open suspend fun publish(id: String): Boolean {
        val candidate = checkNotNull(version(id))
        if (active(candidate.domain)?.id == id) return false
        check(candidate.state == "READY" && validation(id)?.candidateDigest == candidate.digest)
        check(active(candidate.domain)?.id == candidate.baseVersion) { "vector_base_changed" }
        requireComplete(candidate)
        check(candidateDigest(candidate) == candidate.digest) { "vector_candidate_corrupt" }
        candidate.baseVersion?.let { old ->
            val previous = checkNotNull(version(old))
            setState(old, "RETIRED", previous.digest)
        }
        setActive(VectorActiveEntity(candidate.domain, id))
        setState(id, "PUBLISHED", candidate.digest)
        return true
    }

    @Transaction
    open suspend fun rollback(domain: String, expectedActive: String, target: String, space: String): Boolean {
        val current = checkNotNull(active(domain))
        check(current.id == expectedActive && current.baseVersion == target) { "vector_base_changed" }
        val previous = checkNotNull(version(target))
        check(previous.domain == domain && previous.state == "RETIRED" && previous.space == space) {
            "vector_rollback_space_changed"
        }
        check(validation(target)?.candidateDigest == previous.digest) { "vector_rollback_receipt_missing" }
        requireComplete(previous)
        check(candidateDigest(previous) == previous.digest) { "vector_candidate_corrupt" }
        setState(current.id, "RETIRED", current.digest)
        setActive(VectorActiveEntity(domain, target))
        setState(target, "PUBLISHED", previous.digest)
        return true
    }

    @Transaction
    open suspend fun abandon(id: String) {
        val candidate = checkNotNull(version(id))
        check(candidate.state in setOf("BUILDING", "VALIDATING", "READY", "FAILED"))
        setState(id, "FAILED", candidate.digest)
    }

    private suspend fun requireComplete(candidate: VectorVersionEntity) {
        check(sourceCount(candidate.domain) == candidate.expectedCount && countEntries(candidate.id) == candidate.expectedCount) {
            "vector_candidate_incomplete"
        }
        var after = ""
        while (true) {
            val sources = sourcePage(candidate.domain, after, 50)
            if (sources.isEmpty()) break
            val entries = candidatePage(candidate.id, after, sources.size)
            check(sources.size == entries.size && sources.zip(entries).all { (source, entry) ->
                source.id == entry.sourceId && source.text == entry.sourceText &&
                    source.title == entry.sourceTitle && source.heading == entry.sourceHeading
            }) { "vector_source_changed" }
            after = sources.last().id
        }
    }

    private suspend fun candidateDigest(candidate: VectorVersionEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun bytes(value: ByteArray) {
            digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
            digest.update(value)
        }
        listOf(candidate.id, candidate.domain, candidate.space, candidate.dimensions.toString()).forEach { bytes(it.toByteArray(Charsets.UTF_8)) }
        var after = ""
        while (true) {
            val entries = candidatePage(candidate.id, after, 50)
            if (entries.isEmpty()) break
            entries.forEach { entry ->
                validateVector(entry.vector, candidate.dimensions)
                listOf(entry.sourceId, entry.sourceText, entry.sourceTitle, entry.sourceHeading).forEach { bytes(it.toByteArray(Charsets.UTF_8)) }
                bytes(entry.vector)
            }
            after = entries.last().sourceId
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateVector(bytes: ByteArray, dimensions: Int) {
        require(bytes.size == dimensions * 4) { "vector_dimension_mismatch" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var norm = 0.0
        repeat(dimensions) {
            val value = buffer.float
            require(value.isFinite()) { "vector_non_finite" }
            norm += value.toDouble() * value
        }
        require(norm > 0.0 && norm.isFinite()) { "vector_zero_norm" }
    }
}
