package com.zero.v2.core.data.entity

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class MemorySearchQueryTest {
    private fun frozen() = checkNotNull(javaClass.getResourceAsStream("/m5-search-frozen.tsv"))
        .bufferedReader().use { it.readLines().filter(String::isNotBlank).map { line -> line.split('\t') } }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("invalid query accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun subjectMatchingRejectsAttributeOnlyAndUnrelatedSubjects() {
        for (c in frozen().filter { it[0] == "conflict" }) assertEquals(c[1], c[3].toBoolean(), MemorySearchQuery.matchesSubject(c[1], c[2]))
        assertTrue(MemorySearchQuery.matchesSubject("书房 灯具", "家庭/书房/灯具"))
        assertFalse(MemorySearchQuery.matchesSubject("灯具", "家庭/书房/灯具"))
    }
    @Test fun normalizationIsBoundedAndDoesNotInventSynonyms() {
        assertEquals(listOf("note", "archive"), MemorySearchQuery.terms(" NOTE，archive;note "))
        assertEquals(listOf("展柜", "海蓝"), MemorySearchQuery.terms("展柜\u3000海蓝\u00a0展柜"))
        assertEquals(listOf("2026-09-12", "装配"), MemorySearchQuery.terms("2026-09-12 装配"))
        assertEquals(listOf("%'_"), MemorySearchQuery.terms("%'_"))
        assertTrue(MemorySearchQuery.terms("  ").isEmpty())
    }
    @Test fun invalidQueriesDoNotSilentlyBroadenSearch() {
        rejects { MemorySearchQuery.terms("a ".repeat(101)) }
        rejects { MemorySearchQuery.terms((1..9).joinToString(" ")) }
        rejects { MemorySearchQuery.terms("，；") }
        rejects { MemorySearchQuery.sources("展柜", 9) }
        rejects { MemorySearchQuery.slots("", "灯架") }
    }
    @Test fun sqlOnlyBindsCallerTextIncludingScopeAndWildcards() {
        val query = "%'_ OR 1=1 --"
        val plan = MemorySearchQuery.sources(query, 3)
        assertFalse(plan.sql.contains("1=1 --")); assertTrue(plan.args.contains("%'_"))
        assertEquals(plan.args.size, plan.sql.count { it == '?' })
        val scoped = checkNotNull(MemorySearchQuery.slots("scope'--", "灯架 材质"))
        assertFalse(scoped.sql.contains("scope'--")); assertEquals("scope'--", scoped.args.first())
        assertEquals(scoped.args.size, scoped.sql.count { it == '?' })
    }
    /** Export the actual production SQL/arguments for host SQLite execution, not a second query implementation. */
    @Test fun exportFrozenPlansForIndependentSqliteExecution() {
        val cases = frozen(); assertEquals(13, cases.size)
        val b64 = Base64.getEncoder()
        fun encode(s: String) = b64.encodeToString(s.toByteArray(Charsets.UTF_8))
        val lines = cases.map { c ->
            val plan = if (c[0] == "source") MemorySearchQuery.sources(c[1], 8) else checkNotNull(MemorySearchQuery.slots("frozen", c[1]))
            listOf(c[0], c[3], encode(c[2]), encode(plan.sql), plan.args.joinToString(",") {
                (if (it is Number) "n:" else "s:") + encode(it.toString())
            }).joinToString("\t")
        }
        File("build/m5-search-sql-plans.tsv").apply { parentFile.mkdirs(); writeText(lines.joinToString("\n")) }
    }
}
