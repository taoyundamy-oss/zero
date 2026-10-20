package com.zero.v2.services.retrieval

import com.zero.v2.core.common.EvidenceDomain
import com.zero.v2.core.common.EvidenceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R3：EvidencePolicy 证据选型 + 格式化纯逻辑测试。 */
class EvidencePolicyTest {

    @Test fun `qualified sources retain suffix qualifiers or become references only`() {
        val complete = mem("derived:abc", "资料".repeat(120) + "只有核实后才行动，目前没有完成").copy(
            completeSource = true, sourceVersion = "v1")
        val whole = EvidencePolicy.formatBlock(listOf(complete))!!
        assertTrue(whole.contains(complete.content))
        val reference = EvidencePolicy.formatBlock(listOf(complete.copy(content = "资料".repeat(600))))!!
        assertFalse(reference.contains("资料资料"))
        assertTrue(reference.contains("memory_recall"))
        assertTrue(reference.contains("derived:abc"))
        assertTrue(reference.length <= 800)
    }

    @Test fun `same source body with different provenance and current scope is not collapsed`() {
        val first = mem("a").copy(completeSource = true, bodyDigest = "same", sourceVersion = "v1")
        val second = mem("b").copy(completeSource = true, bodyDigest = "same", sourceVersion = "v2")
        assertEquals(2, EvidencePolicy.selectTopK(listOf(first, second), emptyList()).size)
    }

    private fun mem(id: String, content: String = "记忆-$id") =
        EvidenceRef(EvidenceDomain.MEMORY, id, content, "memory", 0.5f)
    private fun kb(id: String, content: String = "知识-$id") =
        EvidenceRef(EvidenceDomain.KNOWLEDGE, id, content, "knowledge/n.md", 0.5f)

    // ═══ selectTopK ═══

    @Test
    fun `body digest keeps standard SHA256 bytes and never ignores suffixes`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            EvidencePolicy.bodyDigest("abc"))
        val prefix = "更正".repeat(200)
        assertFalse(EvidencePolicy.bodyDigest(prefix + "上午") == EvidencePolicy.bodyDigest(prefix + "下午"))
    }

    @Test
    fun `identical bodies are compressed without introducing a lower ranked source`() {
        val first = mem("same", "[历史来源未核验] 发布要求").copy(bodyDigest = "same-full-body")
        val duplicate = kb("same", "发布要求").copy(bodyDigest = "same-full-body")
        val second = kb("manual", "部署手册").copy(bodyDigest = "different-full-body")
        val top = EvidencePolicy.selectTopK(listOf(first), listOf(duplicate, second), k = 3)
        assertEquals(2, top.size)
        assertTrue("required second document originally in top-k must remain visible", top.any { it.id == "manual" })
        val limited = EvidencePolicy.selectTopK(listOf(first), listOf(duplicate, second), k = 2)
        assertEquals(1, limited.size)
        assertFalse("do not refill with an unseen lower-ranked source", limited.any { it.id == "manual" })
    }

    @Test
    fun `different full bodies with identical excerpts remain separate and legacy refs stay compatible`() {
        val prefix = "a".repeat(200)
        val first = mem("same", prefix).copy(bodyDigest = EvidencePolicy.bodyDigest(prefix + "第一版"))
        val second = kb("same", prefix).copy(bodyDigest = EvidencePolicy.bodyDigest(prefix + "第二版"))
        assertEquals(2, EvidencePolicy.selectTopK(listOf(first), listOf(second), k = 3).size)
        assertEquals(2, EvidencePolicy.selectTopK(listOf(mem("same", "相同")),
            listOf(kb("same", "相同")), k = 3).size)
        assertTrue(EvidencePolicy.selectTopK(listOf(first), listOf(second), k = 0).isEmpty())
    }

    @Test
    fun `selectTopK returns at most k fused refs`() {
        val mem = (1..5).map { mem(it.toString()) }
        val kb = (1..5).map { kb(it.toString()) }
        val top = EvidencePolicy.selectTopK(mem, kb, k = 3)
        assertTrue(top.size <= 3)
    }

    @Test
    fun `selectTopK fuses both domains`() {
        val mem = listOf(mem("a"))
        val kb = listOf(kb("b"))
        val top = EvidencePolicy.selectTopK(mem, kb, k = 3)
        assertEquals(2, top.size)
        val domains = top.map { it.domain }.toSet()
        assertTrue(domains.contains(EvidenceDomain.MEMORY))
        assertTrue(domains.contains(EvidenceDomain.KNOWLEDGE))
    }

    @Test
    fun `selectTopK sets score from rrf fusion`() {
        val mem = listOf(mem("a"))
        val top = EvidencePolicy.selectTopK(mem, emptyList(), k = 3)
        assertEquals(1, top.size)
        // 单路 rank0 → 1/(K+1)
        val expected = 1f / (RetrievalKit.RRF_K + 1)
        assertEquals(expected, top.first().score, 0.0001f)
    }

    @Test
    fun `selectTopK empty when both domains empty`() {
        val top = EvidencePolicy.selectTopK(emptyList(), emptyList(), k = 3)
        assertTrue(top.isEmpty())
    }

    // ═══ formatBlock ═══

    @Test
    fun `formatBlock null when empty refs`() {
        assertNull(EvidencePolicy.formatBlock(emptyList()))
    }

    @Test
    fun `formatBlock includes source refs and content`() {
        val refs = listOf(
            mem("1", "用户住在邵阳市双清区"),
            kb("2", "方剂正文片段"),
        )
        val block = EvidencePolicy.formatBlock(refs)
        assertTrue(block != null)
        assertTrue(block!!.contains("[来源：memory]"))
        assertTrue(block.contains("[来源：knowledge/n.md]"))
        assertTrue(block.contains("邵阳市双清区"))
        assertTrue(block.contains("方剂正文片段"))
        assertTrue(block.startsWith("[相关证据]"))
    }

    @Test
    fun `formatBlock caps at 3 refs`() {
        val refs = (1..6).map { mem(it.toString(), "内容$it") }
        val block = EvidencePolicy.formatBlock(refs)!!
        // ≤3 条来源行（第 4-6 条不入）
        val sourceCount = block.split("[来源：").size - 1
        assertTrue("来源行 ≤3", sourceCount <= 3)
    }

    @Test
    fun `formatBlock truncates per-ref content to cap`() {
        val longContent = "a".repeat(500)
        val refs = listOf(mem("1", longContent))
        val block = EvidencePolicy.formatBlock(refs)!!
        // 每条正文 ≤ PER_REF_CONTENT_CAP
        val body = block.substringAfter("[来源：memory] ")
        assertTrue("正文截断至 ${EvidencePolicy.PER_REF_CONTENT_CAP}", body.length <= EvidencePolicy.PER_REF_CONTENT_CAP)
    }

    @Test
    fun `formatBlock respects maxChars budget`() {
        val refs = (1..3).map { mem(it.toString(), "b".repeat(300)) }
        val block = EvidencePolicy.formatBlock(refs, maxChars = 100)
        // 总长 ≤ 100 或装不下→null
        if (block != null) assertTrue(block.length <= 100)
    }

    @Test
    fun `formatBlock null when no ref fits maxChars`() {
        val refs = listOf(mem("1", "b".repeat(500)))
        val block = EvidencePolicy.formatBlock(refs, maxChars = 10)
        assertNull("maxChars 极小装不下完整条目→不注入", block)
    }
}
