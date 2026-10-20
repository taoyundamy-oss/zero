package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** R2：EvidenceRef 统一读投影 + 跨域去重 key 纯逻辑测试。 */
class EvidenceRefTest {

    @Test
    fun `memory and knowledge domains have distinct prefixes`() {
        assertEquals("mem", EvidenceDomain.MEMORY.prefix)
        assertEquals("kb", EvidenceDomain.KNOWLEDGE.prefix)
    }

    @Test
    fun `key is domain-prefixed`() {
        val mem = EvidenceRef(EvidenceDomain.MEMORY, "abc", "用户住在邵阳", "memory", 0.9f)
        val kb = EvidenceRef(EvidenceDomain.KNOWLEDGE, "abc", "方剂正文片段", "knowledge/中医/方剂.md", 0.5f)
        assertEquals("mem:abc", mem.key())
        assertEquals("kb:abc", kb.key())
    }

    @Test
    fun `same id different domain does not collide`() {
        val mem = EvidenceRef(EvidenceDomain.MEMORY, "x1", "记忆内容", "memory")
        val kb = EvidenceRef(EvidenceDomain.KNOWLEDGE, "x1", "知识片段", "knowledge/note.md")
        assertNotEquals(mem.key(), kb.key())
    }

    @Test
    fun `key is deterministic across constructions`() {
        val a = EvidenceRef(EvidenceDomain.MEMORY, "id-9", "c", "memory", 0.1f)
        val b = EvidenceRef(EvidenceDomain.MEMORY, "id-9", "不同内容", "memory", 0.9f)
        assertEquals(a.key(), b.key())
    }

    @Test
    fun `fields preserved`() {
        val ref = EvidenceRef(EvidenceDomain.KNOWLEDGE, "chunk-7", "正文片段", "knowledge/中医/方剂.md", 0.42f)
        assertEquals(EvidenceDomain.KNOWLEDGE, ref.domain)
        assertEquals("chunk-7", ref.id)
        assertEquals("正文片段", ref.content)
        assertEquals("knowledge/中医/方剂.md", ref.sourceRef)
        assertEquals(0.42f, ref.score, 0.0001f)
    }

    @Test
    fun `default score is zero`() {
        val ref = EvidenceRef(EvidenceDomain.MEMORY, "id", "内容", "memory")
        assertEquals(0f, ref.score, 0.0001f)
    }
}
