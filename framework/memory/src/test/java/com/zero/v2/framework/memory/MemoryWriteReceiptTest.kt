package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryDerivationEntity
import com.zero.v2.core.data.store.MemoryDerivationStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryWriteReceiptTest {
    private val d = MemoryDerivationEntity.create("0".repeat(64), "不得擅自删除", "不得擅自删除", MemoryType.DIRECTIVE,
        "EXPLICIT", "personal", "文件/删除", .8f, now = 1)
    @Test fun pendingIsDurableButNotMaterializedAndWakeFailureIsVisible() {
        val r = JSONObject(MemoryWriteReceipt.render(d, MemoryDerivationStore.WriteReceipt("PENDING", null), true))
        assertTrue(r.getBoolean("persisted")); assertFalse(r.getBoolean("materialized"))
        assertFalse(r.getBoolean("background_wake_requested")); assertFalse(r.getBoolean("usable_as_current_fact"))
        assertEquals("PENDING", r.getString("state")); assertTrue(r.isNull("version"))
    }
    @Test fun duplicateSubmissionUsesCurrentQueueStateRatherThanSavedDraft() {
        val receipt = MemoryDerivationStore.WriteReceipt("APPLIED", MemoryDerivationStore.Receipt("ACTIVE", "v"))
        val r = JSONObject(MemoryWriteReceipt.render(d, receipt, false))
        assertEquals("APPLIED", r.getString("state")); assertTrue(r.getBoolean("materialized"))
        assertEquals("v", r.getString("version")); assertFalse(r.getBoolean("usable_as_current_fact"))
    }
}
