package com.zero.v2.services.retrieval

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.ChronicleSourceEntity
import com.zero.v2.core.data.entity.ClassifiedMemorySnapshot
import com.zero.v2.core.data.entity.MemoryDerivationEntity
import com.zero.v2.core.data.entity.MemoryDerivationProjection
import com.zero.v2.core.data.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClassifiedMemoryReaderTest {
    private val body = "周末准备修院门。" + "现场情况尚待确认。".repeat(30) + "只有材料按时送到才动工，目前没有完成，也不能认定延期是天气造成。"
    private val source = ChronicleSourceEntity.message(MessageEntity("s", "c", "user", body,
        source = "CHAT_WINDOW", createdAt = 10), 20)!!
    private fun row(s: ChronicleSourceEntity = source, scope: String = "personal", content: String = "准备修院门"):
        MemoryDerivationProjection.Snapshot {
        val d = MemoryDerivationEntity.create(s.id, content, s.body, MemoryType.EVENT, "EXPLICIT", scope,
            "院门/进度", 0.8f, validFrom = 100, validUntil = 200, now = 30).copy(state = "APPLIED", appliedAt = 31)
        return MemoryDerivationProjection.Snapshot(d, s, d.memory(s))
    }
    private fun snapshot(r: MemoryDerivationProjection.Snapshot = row(), now: Long = 150,
        others: List<MemoryDerivationProjection.Snapshot> = emptyList()) = ClassifiedMemorySnapshot(r,
        MemoryDerivationProjection.rebuild(r.entry.slotId, r.entry.scope, listOf(r) + others, now), false)

    @Test fun completeSourceIncludesLateNegationAndDoesNotCertifyTheDeclaration() = runBlocking {
        val reader = ClassifiedMemoryReader { _, now -> snapshot(now = now) }
        val ticket = reader.capture(row().entry.memoryId, now = 150).ticket!!
        assertEquals(body, ticket.text)
        assertTrue(ticket.payload.contains("没有完成"))
        assertTrue(ticket.payload.contains("不等于问题支持"))
        assertTrue(ticket.payload.contains("STRUCTURAL_CANDIDATE"))
        assertTrue(reader.revalidate(ticket, 199))
        assertFalse(reader.revalidate(ticket, 200))
    }
    @Test fun viewCacheCannotExtendTheExplicitInterval() = runBlocking {
        val reader = ClassifiedMemoryReader { _, now -> snapshot(now = now) }
        assertNull(reader.capture("id", now = 99).ticket)
        assertNotNull(reader.capture("id", now = 100).ticket)
    }
    @Test fun scopeAndDeclaredSlotConflictFailClosed() {
        val reader = ClassifiedMemoryReader { _, _ -> null }
        assertEquals("scope_mismatch", reader.qualify(snapshot(), "work").reason)
        val conflicting = row(content = "院门已经修好")
        assertNull(reader.qualify(snapshot(others = listOf(conflicting)), "personal").ticket)
    }
    @Test fun conflictDiagnosticsNeverCreateNormalConsumptionTickets() = runBlocking {
        var current: ClassifiedMemorySnapshot? = snapshot(others = listOf(row(content = "另一份相互矛盾的声明")))
        val reader = ClassifiedMemoryReader { _, _ -> current }
        val ticket = reader.captureConflictDiagnostic("id", now = 150).ticket!!
        assertEquals(body, ticket.text); assertTrue(ticket.payload.contains("CONFLICT_DIAGNOSTIC_ONLY"))
        assertNull(reader.capture("id", now = 150).ticket)
        assertFalse(reader.revalidate(ticket, 150))
        assertEquals(ticket, reader.captureConflictDiagnostic("id", now = 150).ticket)
        current = null
        assertNull(reader.captureConflictDiagnostic("id", now = 150).ticket)
    }
    @Test fun diagnosticExceptionDoesNotBypassOriginScopeLifetimeOrReplacement() = runBlocking {
        var current = snapshot(others = listOf(row(content = "另一份相互矛盾的声明")))
        val reader = ClassifiedMemoryReader { _, now -> current.copy(view = MemoryDerivationProjection.rebuild(
            current.row.entry.slotId, current.row.entry.scope, listOf(current.row.copy(conflict = true)), now)) }
        assertNull(reader.captureConflictDiagnostic("id", "work", 150).ticket)
        assertNull(reader.captureConflictDiagnostic("id", now = 99).ticket)
        assertNull(reader.captureConflictDiagnostic("id", now = 200).ticket)
        current = current.copy(replaced = true)
        assertNull(reader.captureConflictDiagnostic("id", now = 150).ticket)
        val unverified = ChronicleSourceEntity.message(MessageEntity("automation", "c", "user", body,
            source = "CHAT_WINDOW", automationRunId = "run", createdAt = 10), 20)!!
        current = snapshot(row(unverified).copy(conflict = true))
        assertEquals("source_origin_unverified", reader.captureConflictDiagnostic("id", now = 150).reason)
    }
    @Test fun pendingConflictArchiveReplacementAndDeletionWithdrawTheTicket() = runBlocking {
        var current: ClassifiedMemorySnapshot? = snapshot()
        val reader = ClassifiedMemoryReader { _, _ -> current }
        val ticket = reader.capture("id", now = 150).ticket!!
        current = snapshot(row().copy(conflict = true))
        assertFalse(reader.revalidate(ticket, 150))
        current = snapshot(row().let { it.copy(memory = it.memory!!.copy(status = MemoryStatus.ARCHIVED)) })
        assertFalse(reader.revalidate(ticket, 150))
        current = snapshot().copy(replaced = true)
        assertFalse(reader.revalidate(ticket, 150))
        current = null
        assertFalse(reader.revalidate(ticket, 150))
    }
    @Test fun automationAndUnknownChannelCannotBecomeUserEvidence() {
        val reader = ClassifiedMemoryReader { _, _ -> null }
        for (message in listOf(
            MessageEntity("x", "c", "user", body, source = "CHAT_WINDOW", automationRunId = "run", createdAt = 10),
            MessageEntity("x", "c", "user", body, source = null, createdAt = 10),
            MessageEntity("x", "c", "user", body, source = "REMOTE", triggerId = "trigger", createdAt = 10))) {
            val s = ChronicleSourceEntity.message(message, 20)!!
            assertEquals("source_origin_unverified", reader.qualify(snapshot(row(s)), "personal").reason)
        }
    }
    @Test fun editedOrSubstitutedBindingDoesNotRetainQualification() {
        val reader = ClassifiedMemoryReader { _, _ -> null }
        val r = row()
        assertNull(reader.qualify(snapshot(r.copy(memory = r.memory!!.copy(content = "已完成"))), "personal").ticket)
        assertNull(reader.qualify(snapshot(r.copy(source = source.copy(body = "已完成"))), "personal").ticket)
    }
    @Test fun cancellationPropagatesInsteadOfReturningQualifiedFallback() = runBlocking {
        val reader = ClassifiedMemoryReader { _, _ -> throw kotlinx.coroutines.CancellationException("cancel") }
        try { reader.capture("id"); fail("cancelled") } catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun expiredHistoryIsReadableOnlyAsDiagnosticAndCannotBeConsumed() = runBlocking {
        val reader = ClassifiedMemoryReader { _, now -> snapshot(now = now) }
        val ticket = checkNotNull(reader.captureHistory(row().entry.memoryId, "personal", 200).ticket)
        assertTrue(ticket.payload.contains("HISTORY_DIAGNOSTIC_ONLY"))
        assertTrue(org.json.JSONObject(ticket.payload).getBoolean("expired"))
        assertNull(reader.capture(row().entry.memoryId, now = 200).ticket)
        assertFalse(reader.revalidate(ticket, 150))
        assertNull(reader.captureHistory(row().entry.memoryId, "work", 200).ticket)
        val blocked = ClassifiedMemoryReader { _, now -> snapshot(row().copy(blocked = true), now) }
        assertNull(blocked.captureHistory(row().entry.memoryId, "personal", 200).ticket)
    }

}
