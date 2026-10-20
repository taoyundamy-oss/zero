package com.zero.v2.app.knowledge

import com.zero.v2.core.data.dao.KbDao
import com.zero.v2.core.data.entity.KbDocEntity
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Candidate
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Job
import com.zero.v2.core.data.knowledge.KnowledgeCandidateStore.Companion.fingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A persisted text rebuild. Its caller owns the index mutex and supplies the publication mutex. */
internal class KnowledgeRebuild(
    private val source: KnowledgeSource,
    private val dao: KbDao,
    private val store: KnowledgeCandidateStore,
) {
    fun pending(): Boolean = try {
        store.job()?.state in setOf("BUILDING", "READY")
    } catch (failure: Exception) {
        store.save(Job("", "", null, "FAILED"))
        throw failure
    }

    suspend fun run(
        requestedPath: String?,
        resume: Boolean,
        maxDocs: Int,
        maxChunks: Int,
        prepare: (String, String, Long, Long, KbDocEntity?) -> Candidate,
        publish: suspend (Int, List<String>, String, suspend (Int) -> Candidate) -> Unit,
    ) {
        var job: Job? = null
        try {
            val previous = if (resume) store.job() else null
            job = previous
            val path = if (resume) previous?.path else requestedPath
            val root = source.identity()
            check(root.isNotEmpty() && source.hasDirectory()) { "reindex_no_source" }
            val listing = source.listTree()
            check(listing.complete) { "reindex_incomplete_listing" }
            val nodes = listing.nodes.filter {
                !it.isDir && it.path.startsWith("knowledge/") && it.path.endsWith(".md")
            }.sortedBy { it.path }
            val selected = nodes.filter { path == null || it.path == "knowledge/$path" }
            check(path == null || selected.isNotEmpty()) { "reindex_source_missing" }
            check(selected.size <= maxDocs) { "reindex_document_limit" }
            val signature = fingerprint("$root\n$path\n$maxDocs\n$maxChunks\n$nodes")
            val baseline = dao.loadAllDocs()
            val baselineHash = fingerprint(baseline.toString())
            if (previous != null) {
                check(previous.source == signature) { "reindex_source_changed" }
                job = previous
            } else {
                job = Job(signature, baselineHash, path, "BUILDING")
                store.begin(job)
            }
            val existing = baseline.associateBy { it.relativePath }
            val removed = if (path != null) emptyList() else baseline.map { it.relativePath }
                .filter { rel -> nodes.none { it.path == "knowledge/$rel" } }
            selected.forEachIndexed { index, node ->
                coroutineContext.ensureActive()
                val text = source.readText(node.path) ?: error("reindex_read_failed")
                check(text.length <= 4_000_000) { "reindex_document_budget" }
                val hash = fingerprint(text)
                val saved = store.candidate(index)
                if (saved != null) {
                    check(saved.sourceHash == hash && saved.doc.relativePath == node.path.removePrefix("knowledge/")) {
                        "reindex_source_changed"
                    }
                } else {
                    check(job.baseline == baselineHash && job.state == "BUILDING") { "reindex_active_changed" }
                    val rel = node.path.removePrefix("knowledge/")
                    val candidate = prepare(rel, text, node.lastModified, node.sizeBytes, existing[rel])
                    check(candidate.chunks.size <= maxChunks) { "reindex_chunk_limit" }
                    store.put(index, candidate.copy(sourceHash = hash))
                }
            }
            // SAF providers can retain size/mtime on replacement; compare content as well as metadata.
            selected.forEachIndexed { index, node ->
                coroutineContext.ensureActive()
                val text = source.readText(node.path) ?: error("reindex_read_failed")
                check(fingerprint(text) == store.candidate(index)?.sourceHash) { "reindex_source_changed" }
            }
            val verified = source.listTree()
            check(verified.complete && verified.nodes.filter {
                !it.isDir && it.path.startsWith("knowledge/") && it.path.endsWith(".md")
            }.sortedBy { it.path } == nodes && source.identity() == root) { "reindex_source_changed" }
            coroutineContext.ensureActive()
            job = job.copy(state = "READY")
            store.save(job)
            publish(selected.size, removed, job.baseline) { index ->
                coroutineContext.ensureActive()
                store.candidate(index) ?: error("reindex_candidate_missing")
            }
            store.save(job.copy(state = "COMMITTED"))
        } catch (cancelled: CancellationException) {
            job?.let { runCatching { store.save(it.copy(state = "CANCELLED")) }.exceptionOrNull()?.let(cancelled::addSuppressed) }
            throw cancelled
        } catch (failure: Exception) {
            runCatching { store.save((job ?: Job("", "", requestedPath, "FAILED")).copy(state = "FAILED")) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}
