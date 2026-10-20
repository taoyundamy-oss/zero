package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.skills.SkillStore
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MessageRole
import com.zero.v2.framework.chat.MemoryContextStore
import com.zero.v2.framework.chat.ToolManifestStore
import com.zero.v2.services.retrieval.ContextMemory
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses isolated cache fixtures; only migration opens the real database, with normal startup suppressed. */
class PersistentSystemAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun realDatabaseOpensAtExpectedSchema() {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ProviderAcceptanceEntryPoint::class.java)
        val db = entry.database().openHelper.writableDatabase
        db.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(38, it.getInt(0)) }
        db.query("PRAGMA quick_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        db.query("SELECT count(*) FROM memory_sources").close()
        db.query("SELECT count(*) FROM memory_conflicts").close()
    }

    @Test fun skillAtomicHistorySurvivesReopenAndVerificationCannotSurviveEditing() {
        val root = File(context.cacheDir, "p7-skills-${Process.myPid()}")
        try {
            val body = listOf("用途", "触发条件", "前提", "工具", "步骤", "成功判据", "失败恢复")
                .joinToString("\n") { "## $it\n合成测试" }
            val first = SkillStore(root).write("p7-fixture", "", body, "synthetic-p7")
            val store = SkillStore(root)
            assertEquals(first, store.read("p7-fixture"))
            val verified = store.confirmVerification(first.id, first.version, "独立合成任务核验")
            assertEquals("user_confirmed", SkillStore(root).read(first.id)!!.status)
            val updated = store.write(first.id, verified.version, "$body\n修改", "synthetic-p7-2")
            assertEquals("candidate", updated.status)
            assertTrue(store.delete(first.id, updated.version))
            assertNull(SkillStore(root).read(first.id))
            val restored = store.write(first.id, "", "", "synthetic-restore", verified.version)
            assertEquals("candidate", restored.status)
            assertFalse(restored.markdown.contains("verificationEvidence"))
        } finally { assertTrue(root.deleteRecursively()) }
    }

    @Test fun memoryAndToolsSurviveRealProcessRestart() {
        val phase = InstrumentationRegistry.getArguments().getString("p7Phase")
        org.junit.Assume.assumeTrue(phase in setOf("seed", "verify"))
        val root = File(context.cacheDir, "p7-recovery")
        val scoped = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = root }
        val memoryStore = MemoryContextStore(scoped)
        val load = memoryStore.javaClass.methods.single { it.name.startsWith("load") }
        val save = memoryStore.javaClass.methods.single { it.name.startsWith("save") }
        val projection = load.invoke(memoryStore, "synthetic-p7")
        val project = projection.javaClass.getMethod("project", List::class.java, Long::class.javaPrimitiveType,
            List::class.java, Int::class.javaPrimitiveType)
        val memory = ContextMemory("p7", "v1", "合成规范：保留用户数据", "DIRECTIVE", 1)
        val history = listOf(ChatMessage(id="p7-u", role=MessageRole.USER, content="合成问题"))
        fun view(current: List<ContextMemory>): List<ChatMessage> {
            @Suppress("UNCHECKED_CAST")
            return project.invoke(projection, history, 1L, current, 4000) as List<ChatMessage>
        }
        val tool = FunctionDefinition("memory_recall", "synthetic", mapOf("type" to "object"))
        val tools = ToolManifestStore(scoped)
        val select = tools.javaClass.methods.single { it.name.startsWith("select") }
        val reportFile = File(context.cacheDir, "p7-recovery-report.json")
        if (phase == "seed") {
            val messages = view(listOf(memory))
            save.invoke(memoryStore, "synthetic-p7", projection)
            select.invoke(tools, "synthetic-p7", 1L, listOf(tool), emptySet<String>())
            reportFile.writeText(JSONObject().put("seedPid", Process.myPid())
                .put("snapshot", messages.first().content).toString())
        } else {
            val report = JSONObject(reportFile.readText())
            assertNotEquals(report.getInt("seedPid"), Process.myPid())
            val restored = view(listOf(memory))
            assertEquals(report.getString("snapshot"), restored.first().content)
            assertEquals(listOf(tool), select.invoke(tools, "synthetic-p7", 1L,
                listOf(tool.copy(description="changed")), emptySet<String>()))
            val corrected = view(listOf(memory.copy(version="v2", content="合成更正")))
            assertEquals(restored, corrected.take(restored.size))
            assertEquals(MessageRole.USER, corrected.last().role)
            val withdrawn = view(emptyList())
            assertEquals(restored, withdrawn.take(restored.size))
            assertTrue(withdrawn.last().content.orEmpty().contains("撤回 id=p7"))
            assertTrue(root.deleteRecursively())
            report.put("verifyPid", Process.myPid()).put("stablePrefix", true)
                .put("tailCorrection", true).put("withdrawn", true).put("fixtureCleaned", true)
            reportFile.writeText(report.toString(2))
        }
    }
}
