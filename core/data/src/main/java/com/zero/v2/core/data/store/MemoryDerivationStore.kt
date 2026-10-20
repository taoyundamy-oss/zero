package com.zero.v2.core.data.store

import androidx.room.withTransaction
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/** No model dependency. Main-dialogue proposals are the sole classification input. */
@Singleton
class MemoryDerivationStore @Inject constructor(private val db: ZeroDatabase) {
    private val mutex = Mutex()
    data class Prepared(val entry: MemoryDerivationEntity, val source: ChronicleSourceEntity, val memory: MemoryEntity)
    data class Receipt(val status: String, val version: String)

    suspend fun receipt(id: String): Receipt? = transaction {
        db.memoryDao().getById(id)?.let { Receipt(it.status.name, MemoryConflictEntity.version(it)) }
    }

    data class WriteReceipt(val queueState: String, val memory: Receipt?)
    suspend fun writeReceipt(id: String): WriteReceipt? = transaction {
        val d = db.memoryDerivationDao().get(id.removePrefix("derived:")) ?: return@transaction null
        val source = db.chronicleSourceDao().chronicle(d.sourceId) ?: return@transaction null
        if (blocked(source) || db.memoryDao().wasDeleted(d.memoryId)) return@transaction null
        WriteReceipt(d.state, db.memoryDao().getById(d.memoryId)?.let {
            Receipt(it.status.name, MemoryConflictEntity.version(it))
        })
    }

    private suspend fun <T> transaction(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { db.withTransaction { currentCoroutineContext().ensureActive(); block() } }
    }

    suspend fun submit(value: MemoryDerivationEntity): MemoryDerivationEntity = transaction {
        submitInTransaction(value)
    }

    private suspend fun submitInTransaction(value: MemoryDerivationEntity): MemoryDerivationEntity {
        value.validate()
        require(value.state == "PENDING")
        val source = requireNotNull(db.chronicleSourceDao().chronicle(value.sourceId)) { "source_missing_or_deleted" }
        source.validate()
        require(value.boundTo(source)) { "source_quote_missing_ambiguous_or_too_large" }
        require(!blocked(source) && !db.memoryDao().wasDeleted(value.memoryId)) { "source_or_memory_deleted" }
        val old = db.memoryDerivationDao().get(value.id)
        return if (old != null) {
            require(old.copy(createdAt = value.createdAt, state = value.state, appliedAt = value.appliedAt) == value)
            old
        } else {
            require(db.memoryDerivationDao().pending(257).size < 256) { "derivation_queue_full" }
            db.memoryDerivationDao().insert(value)
            rebuildSlot(value.slotId, value.scope, value.createdAt)
            value
        }
    }

    /** Explicit optimistic replacement, not an automatic conflict winner. History/source rows remain. */
    suspend fun replace(value: MemoryDerivationEntity, previousId: String, expectedVersion: String): MemoryDerivationEntity = transaction {
        require(previousId != value.memoryId) { "replacement_unchanged" }
        val old = requireNotNull(db.memoryDao().getById(previousId)) { "replacement_missing" }
        require(old.type != com.zero.v2.core.common.MemoryType.DIRECTIVE ||
            (value.type == com.zero.v2.core.common.MemoryType.DIRECTIVE && value.basis == "EXPLICIT")) { "replacement_cannot_demote_directive" }
        require(MemoryConflictEntity.version(old) == expectedVersion) { "replacement_version_changed" }
        val previous = requireNotNull(db.memoryDerivationDao().get(previousId.removePrefix("derived:"))) { "replacement_requires_bound_memory" }
        require(previous.scope == value.scope && previous.claimKey == value.claimKey) { "replacement_scope_or_key" }
        val saved = submitInTransaction(value)
        val existing = db.memoryDao().getById(saved.memoryId)
        if (existing != null) {
            require(saved.state == "APPLIED" && existing.supersedesId == previousId) { "replacement_identity_in_use" }
        } else {
            require(saved.state == "PENDING" && old.status == MemoryStatus.ACTIVE) { "replacement_not_active" }
            require(!db.memoryDao().hasPendingEvidenceConflict(previousId) && !db.memoryDao().hasActiveEvidenceReplacement(previousId)) {
                "replacement_conflict_requires_confirmation"
            }
            val oldSource = requireNotNull(db.chronicleSourceDao().chronicle(previous.sourceId)) { "replacement_source_gone" }
            require(!blocked(oldSource) && previous.boundTo(oldSource) && previous.matches(old, oldSource)) { "replacement_binding_changed" }
            val source = requireNotNull(db.chronicleSourceDao().chronicle(saved.sourceId))
            db.memoryDao().updateStatus(previousId, MemoryStatus.ARCHIVED)
            db.memoryDao().archiveDerived(previousId)
            db.memoryDao().insert(saved.memory(source).copy(supersedesId = previousId, revision = old.revision + 1))
            check(db.memoryDao().getById(saved.memoryId) != null) { "replacement_materialization_rejected" }
            db.memoryDerivationDao().finish(saved.id, "APPLIED", saved.createdAt)
            rebuildSlot(saved.slotId, saved.scope, saved.createdAt)
        }
        checkNotNull(db.memoryDerivationDao().get(saved.id))
    }

    /** Stop consumption and pending materialization; unlike purge this preserves source/history. */
    suspend fun withdrawMatching(keyword: String, now: Long): Int = transaction {
        require(keyword.isNotBlank())
        val matches = db.memoryDao().withdrawalMatches(keyword)
        val pending = db.memoryDerivationDao().pendingMatching(keyword)
        require(matches.size + pending.size <= 256) { "withdrawal_too_broad_use_specific_keyword" }
        matches.forEach {
            db.memoryDao().updateStatus(it.id, MemoryStatus.ARCHIVED)
            db.memoryDao().archiveDerived(it.id)
        }
        pending.forEach { db.memoryDerivationDao().finish(it.id, "STALE", now) }
        matches.size + pending.size
    }

    suspend fun prepare(id: String): Prepared? = transaction {
        val d = db.memoryDerivationDao().get(id)?.takeIf { it.state == "PENDING" } ?: return@transaction null
        val source = db.chronicleSourceDao().chronicle(d.sourceId) ?: return@transaction null
        d.validate(); source.validate()
        if (blocked(source) || !d.boundTo(source) || db.memoryDao().wasDeleted(d.memoryId)) {
            db.memoryDerivationDao().finish(id, "STALE", System.currentTimeMillis())
            return@transaction null
        }
        Prepared(d, source, d.memory(source))
    }

    suspend fun commit(prepared: Prepared, now: Long): String = transaction {
        val d = prepared.entry
        d.validate(); prepared.source.validate()
        require(d.state == "PENDING" && prepared.memory == d.memory(prepared.source)) { "prepared_payload_changed" }
        val current = db.memoryDerivationDao().get(d.id) ?: return@transaction "GONE"
        if (current != d) return@transaction if (current.state == "APPLIED") "ALREADY_APPLIED" else "STALE"
        val source = db.chronicleSourceDao().chronicle(d.sourceId)
        if (source != prepared.source || blocked(prepared.source) || db.memoryDao().wasDeleted(d.memoryId)) {
            db.memoryDerivationDao().finish(d.id, "STALE", now)
            return@transaction "STALE"
        }
        val existing = db.memoryDao().getById(d.memoryId)
        if (existing != null && (existing.status != MemoryStatus.ACTIVE || !d.matches(existing, prepared.source) ||
                MemoryConflictEntity.version(existing) != MemoryConflictEntity.version(prepared.memory))) {
            db.memoryDerivationDao().finish(d.id, "STALE", now)
            return@transaction "STALE"
        }
        if (existing == null) db.memoryDao().insert(prepared.memory)
        check(db.memoryDao().getById(d.memoryId) != null) { "derivation_materialization_rejected" }
        db.memoryDerivationDao().finish(d.id, "APPLIED", now)
        rebuildSlot(d.slotId, d.scope, now)
        "APPLIED"
    }

    suspend fun drain(limit: Int = 32): Int = withContext(Dispatchers.IO) {
        require(limit in 1..64)
        val jobs = db.memoryDerivationDao().pending(limit)
        var applied = 0
        for (job in jobs) {
            val p = prepare(job.id)
            if (p != null && commit(p, System.currentTimeMillis()) == "APPLIED") applied++
            yield()
        }
        applied
    }

    suspend fun current(scope: String, claimKey: String, now: Long): MemoryDerivationViewEntity = transaction {
        require(scope.isNotBlank() && claimKey.isNotBlank())
        rebuildSlot(MemoryDerivationEntity.slot(scope, claimKey), scope, now)
    }

    suspend fun views(scope: String, now: Long): List<MemoryDerivationViewEntity> = transaction {
        db.memoryDerivationDao().scopedSlots(scope, 64).map { rebuildSlot(it, scope, now) }
    }

    /** Diagnostic evidence only. Never publishes a winner, persists a view, or records access. */
    suspend fun conflictDiagnostics(query: String, scope: String, now: Long,
        memoryId: String? = null): List<MemoryConflictDiagnostic> = transaction {
        require(scope.isNotBlank() && scope.length <= 100)
        val slots = if (memoryId != null) {
            val d = db.memoryDerivationDao().get(memoryId.removePrefix("derived:"))
                ?.takeIf { it.scope == scope } ?: return@transaction emptyList()
            listOf(MemorySlotKey(d.slotId, d.claimKey))
        } else db.memoryDerivationDao().matchingSlots(scope, query)
            .filter { MemorySearchQuery.matchesSubject(query, it.claimKey) }
        val result = mutableListOf<MemoryConflictDiagnostic>()
        for (slot in slots.take(16)) {
            val entries = db.memoryDerivationDao().boundedSlot(slot.slotId, 129)
            if (entries.size > 128) continue // Never omit a side and infer a winner in an overfull slot.
            val rows = readRows(entries)
            val view = MemoryDerivationProjection.rebuild(slot.slotId, scope, rows, now)
            if (view.state != "CONFLICT") continue
            val eligible = rows.filter { row ->
                MemoryDerivationProjection.rebuild(slot.slotId, scope, listOf(row.copy(conflict = false)), now).state == "CANDIDATE"
            }
            result += MemoryConflictDiagnostic(slot.slotId, slot.claimKey, scope, view.version,
                eligible.size, eligible.take(4), eligible.size > 4 || slots.size > 16)
            if (result.size == 2) break
        }
        result
    }

    private suspend fun readRows(entries: List<MemoryDerivationEntity>): List<MemoryDerivationProjection.Snapshot> =
        entries.map { entry ->
            val source = db.chronicleSourceDao().chronicle(entry.sourceId)
            MemoryDerivationProjection.Snapshot(entry, source, db.memoryDao().getById(entry.memoryId),
                source == null || blocked(source) || db.memoryDao().wasDeleted(entry.memoryId),
                db.memoryDao().hasPendingEvidenceConflict(entry.memoryId))
        }

    /** No view/audit/access writes. Overfull slots fail closed rather than omit a conflicting row. */
    suspend fun readCandidate(memoryId: String, now: Long): ClassifiedMemorySnapshot? = transaction {
        if (!memoryId.startsWith("derived:")) return@transaction null
        val d = db.memoryDerivationDao().get(memoryId.removePrefix("derived:")) ?: return@transaction null
        val entries = db.memoryDerivationDao().boundedSlot(d.slotId, 129)
        if (entries.size > 128) return@transaction null
        val rows = readRows(entries)
        val row = rows.singleOrNull { it.entry.id == d.id } ?: return@transaction null
        val source = row.source
        ClassifiedMemorySnapshot(row, MemoryDerivationProjection.rebuild(d.slotId, d.scope, rows, now),
            db.memoryDao().hasActiveEvidenceReplacement(memoryId),
            source?.takeIf { it.kind == "confirmation" }?.let {
                db.memoryDao().readEvidenceSnapshot("review:${it.anchorId}")
            })
    }

    /** Bounded round-robin rebuild. The caller persists neither cursor nor view as evidence. */
    suspend fun sweep(after: String, now: Long): String = transaction {
        val slots = db.memoryDerivationDao().slots(after, 64)
        slots.forEach { slot ->
            db.memoryDerivationDao().inSlot(slot).firstOrNull()?.let { rebuildSlot(slot, it.scope, now) }
        }
        if (slots.size < 64) "" else slots.last()
    }

    /** Metadata-only, bounded pages. Observers may rebuild before the worker, so compare the whole completed view. */
    suspend fun viewFingerprint(): String = transaction {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var after = ""
        while (true) {
            val page = db.memoryDerivationDao().viewPage(after, 64)
            page.forEach { digest.update("${it.slotId.length}:${it.slotId}${it.version.length}:${it.version}".toByteArray(Charsets.UTF_8)) }
            if (page.size < 64) break
            after = page.last().slotId
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun blocked(s: ChronicleSourceEntity): Boolean =
        db.chronicleSourceDao().chronicleBlocked(s.kind, s.anchorId, s.conversationId, s.parentMemoryId)

    private suspend fun rebuildSlot(slot: String, scope: String, now: Long): MemoryDerivationViewEntity {
        val rows = db.memoryDerivationDao().inSlot(slot).map { d ->
            val source = db.chronicleSourceDao().chronicle(d.sourceId)
            MemoryDerivationProjection.Snapshot(d, source, db.memoryDao().getById(d.memoryId),
                source == null || blocked(source) || db.memoryDao().wasDeleted(d.memoryId),
                db.memoryDao().hasPendingEvidenceConflict(d.memoryId))
        }
        val view = MemoryDerivationProjection.rebuild(slot, scope, rows, now)
        if (view.state == "CONFLICT") {
            val candidates = rows.filter { row ->
                MemoryDerivationProjection.rebuild(slot, scope, listOf(row.copy(conflict = false)), now).state == "CANDIDATE"
            }
            // A star of explicit pending pairs bounds writes and isolates every conflicting value.
            val first = candidates.firstOrNull()
            if (first != null) candidates.drop(1).filter { it.entry.meaning() != first.entry.meaning() }.forEach {
                db.memoryDao().insertConflict(MemoryConflictEntity.candidate(checkNotNull(first.memory),
                    checkNotNull(it.memory), "DECLARED_SLOT_CONFLICT", now))
            }
        }
        if (rows.isEmpty()) db.memoryDerivationDao().clearView(slot) else db.memoryDerivationDao().putView(view)
        return view
    }
}
