package com.zero.v2.core.data.entity

import org.junit.Assert.*
import org.junit.Test

class ChronicleSourceEntityTest {
    private val message = MessageEntity("source", "chat", "user", "检查通过后才发布。", createdAt = 10, source = "CHAT_WINDOW")

    @Test fun captureTimeDoesNotRewriteSourceTimeOrIdentity() {
        val a = ChronicleSourceEntity.message(message, 20)!!
        val b = ChronicleSourceEntity.message(message, 30)!!
        assertEquals(10L, a.recordedAt)
        assertEquals(a.id, b.id)
        assertEquals(a, b.copy(capturedAt = 20))
    }
    @Test fun changesCreateNewImmutableIdentity() {
        val a = ChronicleSourceEntity.message(message, 20)!!
        val b = ChronicleSourceEntity.message(message.copy(content = "撤回发布。"), 30)!!
        assertNotEquals(a.id, b.id)
        assertEquals("检查通过后才发布。", a.body)
    }
    @Test fun unknownChannelAndEmptyChannelAreNotConflated() {
        assertNotEquals(ChronicleSourceEntity.message(message.copy(source = null), 20)!!.id,
            ChronicleSourceEntity.message(message.copy(source = ""), 20)!!.id)
    }
    @Test fun assistantAndEmptyInputAreNotUserSource() {
        assertNull(ChronicleSourceEntity.message(message.copy(role = "assistant"), 20))
        assertNull(ChronicleSourceEntity.message(message.copy(source = "AUTONOMOUS"), 20))
        assertNull(ChronicleSourceEntity.message(message.copy(content = " "), 20))
    }
    @Test(expected = IllegalArgumentException::class) fun editedBackupBodyFailsIntegrity() {
        ChronicleSourceEntity.message(message, 20)!!.copy(body = "已发布。").validate()
    }
    @Test(expected = IllegalArgumentException::class) fun negativeTimestampRejected() {
        ChronicleSourceEntity.message(message.copy(createdAt = -1), 20)
    }
}
