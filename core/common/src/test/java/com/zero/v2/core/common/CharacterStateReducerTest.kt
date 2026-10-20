package com.zero.v2.core.common

import com.zero.v2.core.common.CharacterStateReducer.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2：CharacterStateReducer 确定性规则纯逻辑测试。 */
class CharacterStateReducerTest {

    private val reducer = CharacterStateReducer()
    private val now = 1_000_000_000L
    private val cooldownAgo = now - reducer.affinityCooldownMs - 1  // 冷却已过

    private fun input(
        userText: String,
        assistantText: String = "好的",
        affinity: AffinityBand = AffinityBand.ACQUAINTANCE,
        mood: Mood = Mood.NEUTRAL,
        moodExpiresAt: Long = 0,
        lastAffinityChangeAt: Long = cooldownAgo,
    ) = Input(userText, assistantText, affinity, mood, moodExpiresAt, lastAffinityChangeAt, now)

    // ═══ 无关消息不变 ═══

    @Test
    fun `irrelevant weather query yields no affinity change`() {
        val out = reducer.reduce(input("帮我查一下今天天气"))
        assertNull(out.newAffinity)
    }

    @Test
    fun `short message yields no change`() {
        val out = reducer.reduce(input("好的"))
        assertEquals(CharacterStateReducer.Output.NO_CHANGE, out)
    }

    // ═══ 正向信号推进亲密度（+1 档，不跳级）═══

    @Test
    fun `positive signal advances affinity one band`() {
        val out = reducer.reduce(input("我很喜欢你帮我做事的方式"))
        assertEquals(AffinityBand.WARM, out.newAffinity)
        assertEquals(Mood.WARM, out.newMood)
    }

    @Test
    fun `affinity does not skip bands in single turn`() {
        // ACQUAINTANCE + 正向 → WARM（不跳到 CLOSE/DEVOTED）
        val out = reducer.reduce(input("我喜欢你", affinity = AffinityBand.ACQUAINTANCE))
        assertEquals(AffinityBand.WARM, out.newAffinity)
    }

    @Test
    fun `affinity caps at DEVOTED`() {
        val out = reducer.reduce(input("我很爱你", affinity = AffinityBand.DEVOTED))
        assertNull(out.newAffinity)  // 已封顶
    }

    @Test
    fun `affinity accumulates one band per turn from WARM`() {
        val out = reducer.reduce(input("你真好", affinity = AffinityBand.WARM))
        assertEquals(AffinityBand.CLOSE, out.newAffinity)
    }

    // ═══ 冷却（冷却内亲密度不变，情绪仍可变）═══

    @Test
    fun `affinity cooldown blocks change within window`() {
        val withinCooldown = now - 1_000  // 1s 前（冷却内）
        val out = reducer.reduce(input("我喜欢你", lastAffinityChangeAt = withinCooldown))
        assertNull(out.newAffinity)  // 冷却内不变
    }

    @Test
    fun `mood changes even within affinity cooldown`() {
        val withinCooldown = now - 1_000
        val out = reducer.reduce(input("我很喜欢你", lastAffinityChangeAt = withinCooldown))
        assertNull(out.newAffinity)  // 冷却内
        assertEquals(Mood.WARM, out.newMood)  // 情绪不受冷却
    }

    // ═══ 负面/争执不惩罚用户（情绪 concerned，亲密度不降）═══

    @Test
    fun `negative dispute sets concerned mood but does not drop affinity`() {
        val out = reducer.reduce(input("我很生气，讨厌这样", affinity = AffinityBand.WARM))
        assertEquals(Mood.CONCERNED, out.newMood)
        assertNull(out.newAffinity)  // 不降——不自动惩罚用户
    }

    @Test
    fun `negative does not advance affinity`() {
        val out = reducer.reduce(input("烦死了", affinity = AffinityBand.ACQUAINTANCE))
        assertNull(out.newAffinity)
    }

    // ═══ 情绪 TTL 衰减 ═══

    @Test fun `negated positive signals never advance affinity`() {
        for (text in listOf("我不喜欢你", "我不信任你", "我并不开心", "你真烦", "我不觉得舒服")) {
            assertNull(text, reducer.reduce(input(text)).newAffinity)
        }
        assertEquals(Mood.CONCERNED, reducer.reduce(input("我不喜欢你")).newMood)
    }

    @Test fun `quoted and third party affection are not relationship evidence`() {
        for (text in listOf("她说：我喜欢你", "例子是‘我喜欢你’", "我喜欢音乐", "我喜欢他")) {
            assertNull(text, reducer.reduce(input(text)).newAffinity)
        }
    }

    @Test fun `neutral conversation also expires old mood`() {
        assertEquals(Mood.NEUTRAL, reducer.reduce(input("今天读了一本书", mood = Mood.WARM,
            moodExpiresAt = now - 1)).newMood)
    }

    @Test
    fun `expired mood decays to neutral on irrelevant message`() {
        val out = reducer.reduce(input(
            "帮我查天气",
            mood = Mood.WARM,
            moodExpiresAt = now - 1,  // 已过期
        ))
        assertEquals(Mood.NEUTRAL, out.newMood)
        assertNull(out.newAffinity)
    }

    @Test
    fun `unexpired mood persists on irrelevant message`() {
        val out = reducer.reduce(input(
            "帮我查天气",
            mood = Mood.WARM,
            moodExpiresAt = now + 60_000,  // 未过期
        ))
        assertNull(out.newMood)  // 不衰减
    }

    // ═══ 欢快情绪 ═══

    @Test
    fun `cheerful content sets cheerful mood`() {
        val out = reducer.reduce(input("哈哈太棒了"))
        assertEquals(Mood.CHEERFUL, out.newMood)
    }

    @Test
    fun `mood output has ttl expiry`() {
        val out = reducer.reduce(input("我很喜欢你"))
        assertTrue(out.newMoodExpiresAt > now)  // 设了 TTL
    }
}
