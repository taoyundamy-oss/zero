package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CharacterStyleDirective] 单测 — P3 / 设计§6 验收。
 *
 * 覆盖：≤120 token 上限、关闭→null、无状态记录→null（固定 systemPrompt）、各档位产出、
 * 不暴露内部计分（无档位英文名/数值）、确定性。
 */
class CharacterStyleDirectiveTest {

    @Test
    fun disabled_returnsNull() {
        assertNull(CharacterStyleDirective.compose(AffinityBand.WARM, Mood.CHEERFUL, enabled = false))
        // 即便亲密度/情绪非默认，关闭即不注入（固定人设继续回复）
        assertNull(CharacterStyleDirective.compose(AffinityBand.DEVOTED, Mood.CHEERFUL, enabled = false))
    }

    @Test
    fun bothDefault_returnsNull_fixedSystemPrompt() {
        // 无状态记录 → 不注入 → 固定 systemPrompt 继续生效（设计§6 验收项）
        assertNull(CharacterStyleDirective.compose(AffinityBand.DEFAULT, Mood.DEFAULT, enabled = true))
    }

    @Test
    fun neutralMood_acquaintanceAffinity_returnsNull() {
        // 双默认等价（ACQUAINTANCE==DEFAULT, NEUTRAL==DEFAULT）
        assertNull(CharacterStyleDirective.compose(AffinityBand.ACQUAINTANCE, Mood.NEUTRAL, enabled = true))
    }

    @Test
    fun warmMood_producesToneDescriptor() {
        val s = CharacterStyleDirective.compose(AffinityBand.ACQUAINTANCE, Mood.WARM, enabled = true)
        assertNotNull(s)
        assertTrue("应含温和语气", s!!.contains("温和"))
        assertTrue(s.contains("保持既有事实与安全规则"))
    }

    @Test
    fun concernedMood_producesConcernedTone() {
        val s = CharacterStyleDirective.compose(AffinityBand.ACQUAINTANCE, Mood.CONCERNED, enabled = true)
        assertNotNull(s)
        assertTrue(s!!.contains("关切"))
        assertTrue(s.contains("克制"))
    }

    @Test
    fun cheerfulMood_devotedAffinity_producesBothDescriptors() {
        val s = CharacterStyleDirective.compose(AffinityBand.DEVOTED, Mood.CHEERFUL, enabled = true)
        assertNotNull(s)
        assertTrue(s!!.contains("轻快"))
        assertTrue(s.contains("明朗"))
        assertTrue(s.contains("亲切"))
        assertTrue(s.contains("简短"))  // DEVOTED → 可更直接简短
    }

    @Test
    fun neutralMood_warmAffinity_producesWarmthDescriptor() {
        // 情绪默认但亲密度非默认 → 亲切度单独驱动（无语气前缀）
        val s = CharacterStyleDirective.compose(AffinityBand.WARM, Mood.NEUTRAL, enabled = true)
        assertNotNull(s)
        assertTrue(s!!.contains("自然"))
        assertFalse("NEUTRAL 不应加语气前缀", s.contains("温和"))
    }

    @Test
    fun allNonDefaultCombos_withinTokenBudget() {
        // 任何非双默认组合 ≤ MAX_CHARS（≤120 token 保守估）——验收"style directive ≤120 token"
        for (aff in AffinityBand.entries) {
            for (mood in Mood.entries) {
                val s = CharacterStyleDirective.compose(aff, mood, enabled = true)
                if (s != null) {
                    assertTrue("aff=$aff mood=$mood 超长: ${s.length}", s.length <= CharacterStyleDirective.MAX_CHARS)
                }
            }
        }
    }

    @Test
    fun noInternalScoreOrBandNameExposed() {
        // 不暴露内部计分给模型：不出现档位英文名 / "好感" / 数值
        for (aff in AffinityBand.entries) {
            for (mood in Mood.entries) {
                val s = CharacterStyleDirective.compose(aff, mood, enabled = true) ?: continue
                assertFalse("含档位英文名: $s", s.contains("ACQUAINTANCE") || s.contains("WARM")
                    || s.contains("CLOSE") || s.contains("DEVOTED"))
                assertFalse("含 mood 英文名: $s", s.contains("NEUTRAL") || s.contains("CONCERNED")
                    || s.contains("CHEERFUL"))
                assertFalse("含好感字样: $s", s.contains("好感"))
                assertFalse("含数字分: $s", Regex("\\d+").containsMatchIn(s))
            }
        }
    }

    @Test
    fun deterministic_sameInputsSameOutput() {
        val a = CharacterStyleDirective.compose(AffinityBand.CLOSE, Mood.WARM, enabled = true)
        val b = CharacterStyleDirective.compose(AffinityBand.CLOSE, Mood.WARM, enabled = true)
        assertEquals(a, b)
    }

    @Test
    fun differentMood_differentDirective() {
        val warm = CharacterStyleDirective.compose(AffinityBand.ACQUAINTANCE, Mood.WARM, enabled = true)
        val concerned = CharacterStyleDirective.compose(AffinityBand.ACQUAINTANCE, Mood.CONCERNED, enabled = true)
        assertNotNull(warm); assertNotNull(concerned)
        assertTrue(warm!!.contains("温和"))
        assertTrue(concerned!!.contains("关切"))
    }
}
