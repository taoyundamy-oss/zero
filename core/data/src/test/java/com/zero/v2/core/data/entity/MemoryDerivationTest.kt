package com.zero.v2.core.data.entity

import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.common.MemoryType
import org.junit.Assert.*
import org.junit.Test

class MemoryDerivationTest {
    private val source = ChronicleSourceEntity.message(MessageEntity("m", "c", "user", "偏好合成甲，也可能选择合成乙。",
        source = "CHAT_WINDOW", createdAt = 10), 20)!!
    private fun entry(content: String = "合成甲", type: MemoryType = MemoryType.PREFERENCE,
        now: Long = 30) = MemoryDerivationEntity.create(source.id, content, source.body, type,
        "EXPLICIT", "personal", "偏好/合成对象", 0.8f, now = now)
    private fun snapshot(d: MemoryDerivationEntity = entry()) = MemoryDerivationProjection.Snapshot(
        d.copy(state = "APPLIED", appliedAt = 31), source, d.memory(source))
    private fun view(vararg rows: MemoryDerivationProjection.Snapshot, now: Long = 40) =
        MemoryDerivationProjection.rebuild(entry().slotId, "personal", rows.toList(), now)

    @Test fun retriesKeepIdentityAndTypeIsModelChosen() {
        assertEquals(entry(now = 30).id, entry(now = 300).id)
        assertNotEquals(entry().id, entry(type = MemoryType.EVENT).id)
        assertEquals("MODEL_CLASSIFIED", entry().memory(source).origin)
    }
    @Test(expected = IllegalArgumentException::class) fun inferenceCannotDeclareDirective() {
        val d = entry(type = MemoryType.DIRECTIVE)
        d.copy(basis = "INFERRED").validate()
    }
    @Test(expected = IllegalArgumentException::class) fun payloadCannotChangeUnderSameId() {
        entry().copy(content = "替换内容").validate()
    }
    @Test fun quoteMustHaveOneExactLocationInBoundVersion() {
        assertTrue(entry().boundTo(source))
        val d = MemoryDerivationEntity.create(source.id, "候选", "合成", MemoryType.FACT,
            "EXPLICIT", "personal", "key", 0.5f, now = 30)
        assertFalse(d.boundTo(source))
        assertFalse(entry().boundTo(source.copy(id = "other")))
    }
    @Test fun viewIsCandidateNotCertifiedAndRecordedTimeIsNotEventTime() {
        assertEquals("CANDIDATE", view(snapshot()).state)
        assertEquals(null, entry().memory(source).occurredAt)
        assertEquals(30L, entry().memory(source).createdAt)
    }
    @Test fun differentMeaningsConflictInsteadOfLastWriteWinning() {
        val v = view(snapshot(), snapshot(entry("合成乙", now = 50)))
        assertEquals("CONFLICT", v.state)
        assertNull(v.selectedId)
        assertEquals(2, v.eligibleCount)
    }
    @Test fun pendingConflictExcludesSelectionEvenWithoutAnotherVisibleCandidate() {
        assertEquals("CONFLICT", view(snapshot().copy(conflict = true)).state)
    }
    @Test fun deletedSourceOrEditedResultCannotRemainCurrent() {
        assertEquals("INACTIVE", view(snapshot().copy(source = null)).state)
        val row = snapshot()
        assertEquals("INACTIVE", view(row.copy(memory = row.memory!!.copy(content = "用户已编辑"))).state)
        assertEquals("INACTIVE", view(row.copy(blocked = true)).state)
    }
    @Test fun archivedResultCannotBeReactivatedByRebuild() {
        val row = snapshot()
        assertEquals("INACTIVE", view(row.copy(memory = row.memory!!.copy(status = MemoryStatus.ARCHIVED))).state)
    }
    @Test fun validityIsInclusiveStartExclusiveEnd() {
        val d = MemoryDerivationEntity.create(source.id, "合成甲", source.body, MemoryType.PREFERENCE,
            "EXPLICIT", "personal", "偏好/合成对象", 0.8f, validFrom = 100, validUntil = 200, now = 30)
        assertEquals("INACTIVE", view(snapshot(d), now = 99).state)
        assertEquals("CANDIDATE", view(snapshot(d), now = 100).state)
        assertEquals("INACTIVE", view(snapshot(d), now = 200).state)
    }
    @Test fun unchangedEligibilityDoesNotChurnViewVersionWithClock() {
        assertEquals(view(snapshot(), now = 40).version, view(snapshot(), now = 90).version)
    }
    @Test(expected = IllegalArgumentException::class) fun crossScopeRowsAreNotMerged() {
        val d = MemoryDerivationEntity.create(source.id, "候选", source.body, MemoryType.FACT,
            "EXPLICIT", "work", "偏好/合成对象", 0.5f, now = 30)
        view(snapshot(d))
    }
    @Test fun recordingConflictAuditDoesNotChurnAnAlreadyConflictingView() {
        val a = snapshot(); val b = snapshot(entry("合成乙", now = 50))
        assertEquals(view(a, b).version, view(a.copy(conflict = true), b.copy(conflict = true)).version)
        assertNotEquals(view(a).version, view(a.copy(conflict = true)).version)
    }

}
