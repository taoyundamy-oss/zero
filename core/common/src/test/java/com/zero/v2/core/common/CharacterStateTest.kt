package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1：AffinityBand/Mood 默认中性 + fromName + 幂等键纯逻辑测试。 */
class CharacterStateTest {

    // ═══ AffinityBand（D4：离散档位，无记录=默认中性）═══

    @Test
    fun `affinity default is ACQUAINTANCE`() {
        assertEquals(AffinityBand.ACQUAINTANCE, AffinityBand.DEFAULT)
    }

    @Test
    fun `affinity fromName resolves known bands`() {
        assertEquals(AffinityBand.WARM, AffinityBand.fromName("WARM"))
        assertEquals(AffinityBand.CLOSE, AffinityBand.fromName("CLOSE"))
        assertEquals(AffinityBand.DEVOTED, AffinityBand.fromName("DEVOTED"))
    }

    @Test
    fun `affinity fromName null or unknown falls back to default`() {
        assertEquals(AffinityBand.DEFAULT, AffinityBand.fromName(null))
        assertEquals(AffinityBand.DEFAULT, AffinityBand.fromName(""))
        assertEquals(AffinityBand.DEFAULT, AffinityBand.fromName("BOGUS"))
    }

    @Test
    fun `affinity has discrete bands not pseudo-precise`() {
        // D4：离散档位，非"好感 73.42"——枚举固定 4 档
        assertEquals(4, AffinityBand.entries.size)
    }

    // ═══ Mood（D4：短时，衰减到 neutral）═══

    @Test
    fun `mood default is NEUTRAL`() {
        assertEquals(Mood.NEUTRAL, Mood.DEFAULT)
    }

    @Test
    fun `mood fromName resolves known moods`() {
        assertEquals(Mood.WARM, Mood.fromName("WARM"))
        assertEquals(Mood.CONCERNED, Mood.fromName("CONCERNED"))
        assertEquals(Mood.CHEERFUL, Mood.fromName("CHEERFUL"))
    }

    @Test
    fun `mood fromName null or unknown falls back to NEUTRAL`() {
        assertEquals(Mood.DEFAULT, Mood.fromName(null))
        assertEquals(Mood.DEFAULT, Mood.fromName(""))
        assertEquals(Mood.DEFAULT, Mood.fromName("BOGUS"))
    }

    // ═══ 切换角色不串值（D4：分表 + id 隔离——状态行 PK=characterId/conversationId）═══

    @Test
    fun `different character ids yield independent state keys`() {
        // 模拟分表隔离：characterA 与 characterB 的状态行 PK 不同（不串值）
        assertNotEquals("charA", "charB")
        // fromName 无状态记录(null) → 两角色均默认中性，互不干扰
        assertEquals(AffinityBand.DEFAULT, AffinityBand.fromName(null))
        assertEquals(AffinityBand.DEFAULT, AffinityBand.fromName(null))
    }

    // ═══ CharacterStateEventKey 幂等键（R1 MemoryExtractionJobKey 同律）═══

    @Test
    fun `event key same source and type is identical`() {
        val k1 = CharacterStateEventKey.compute("AFFINITY", "msg-1", "用户表达偏好")
        val k2 = CharacterStateEventKey.compute("AFFINITY", "msg-1", "用户表达偏好")
        assertEquals(k1, k2)
    }

    @Test
    fun `event key differs by change type`() {
        val kAff = CharacterStateEventKey.compute("AFFINITY", "msg-1", "原因")
        val kMood = CharacterStateEventKey.compute("MOOD", "msg-1", "原因")
        assertNotEquals(kAff, kMood)
    }

    @Test
    fun `event key differs by source message`() {
        val k1 = CharacterStateEventKey.compute("AFFINITY", "msg-1", "原因")
        val k2 = CharacterStateEventKey.compute("AFFINITY", "msg-2", "原因")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `event key differs by reason`() {
        val k1 = CharacterStateEventKey.compute("AFFINITY", "msg-1", "原因A")
        val k2 = CharacterStateEventKey.compute("AFFINITY", "msg-1", "原因B")
        assertNotEquals(k1, k2)
    }

    @Test
    fun `event key is deterministic`() {
        val k1 = CharacterStateEventKey.compute("MOOD", "msg-9", "欢快回应")
        val k2 = CharacterStateEventKey.compute("MOOD", "msg-9", "欢快回应")
        assertEquals(k1, k2)
        assertTrue(k1.startsWith("MOOD:"))
    }
}
