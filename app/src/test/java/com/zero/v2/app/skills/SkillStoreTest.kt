package com.zero.v2.app.skills

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SkillStoreTest {
    private val body = listOf("用途", "触发条件", "前提", "工具", "步骤", "成功判据", "失败恢复").joinToString("\n") { "## $it\n内容" }
    @Test fun humanVerificationIsVersionBoundAndEditingResetsIt() {
        val root = Files.createTempDirectory("zero-skills-verification").toFile()
        try {
            val store = SkillStore(root)
            val candidate = store.write("example", "", body, "task-1")
            assertTrue(runCatching { store.confirmVerification("example", candidate.version, "") }.isFailure)
            val verified = store.confirmVerification("example", candidate.version, "人工在独立任务中执行，结果与成功判据一致")
            assertEquals("user_confirmed", verified.status)
            assertTrue(verified.markdown.contains(candidate.version))
            assertTrue(runCatching { store.confirmVerification("example", candidate.version, "旧版本") }.isFailure)
            val edited = store.write("example", verified.version, body + "\n修订步骤", "task-2")
            assertEquals("candidate", edited.status)
            assertFalse(edited.markdown.contains("verificationEvidence"))
        } finally { root.deleteRecursively() }
    }
    @Test fun updateRequiresVersionAndDeleteWithdrawsButCanRestoreHistory() {
        val root = Files.createTempDirectory("zero-skills-test").toFile()
        try {
            val store = SkillStore(root)
            val first = store.write("example", "", body, "task-1")
            assertTrue(runCatching { store.write("example", "", body, "task-2") }.isFailure)
            val second = store.write("example", first.version, body + "\n新经验", "task-2")
            assertEquals(first.markdown, store.read("example", first.version)!!.markdown)
            assertTrue(store.delete("example", second.version))
            assertTrue(store.list().isEmpty())
            val restored = store.write("example", "", "", "task-3", first.version)
            assertFalse(restored.markdown.contains("新经验"))
            assertTrue(restored.markdown.contains("candidate"))
            assertTrue(runCatching { store.write("../escape", "", body, "task") }.isFailure)
            assertTrue(runCatching { store.write("invalid", "", "missing sections", "task") }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
