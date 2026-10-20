package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ToolCall
import com.zero.v2.framework.chat.ContextWindow
import com.zero.v2.framework.chat.GenerationProjectionStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/** Two real processes, production store/projection, isolated files, no LLM or module startup. */
class CacheGenerationRecoveryAcceptanceTest {
    @Test fun actualProcessRecovery() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("cachePhase")
        org.junit.Assume.assumeTrue(phase in setOf("seed", "verify"))
        val nonce = requireNotNull(args.getString("cacheNonce"))
        require(nonce.matches(Regex("[a-zA-Z0-9_]{1,64}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(context.cacheDir, "r3-generation-$nonce")
        val scoped = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = isolated
        }
        val report = File(context.cacheDir, "r3-generation-report.json")
        val store = GenerationProjectionStore(scoped)
        val type = Class.forName("com.zero.v2.framework.chat.GenerationProjection")
        val messages = listOf(
            ChatMessage(id = "user", role = MessageRole.USER, content = "fixture"),
            ChatMessage(id = "assistant", role = MessageRole.ASSISTANT, content = "",
                toolCalls = listOf(ToolCall("call", "read_fixture", "{}"))),
            ChatMessage(id = "tool", role = MessageRole.TOOL, content = "fixture".repeat(500), toolCallId = "call"),
            ChatMessage(id = "later", role = MessageRole.USER, content = "later"),
            ChatMessage(id = "answer", role = MessageRole.ASSISTANT, content = "answer"),
            ChatMessage(id = "latest", role = MessageRole.USER, content = "latest"),
        )
        val slice = ContextWindow.sliceForGeneration(messages, 100, { 1 }, true, 100).messages
        val scope = "r3-isolated-model-prefix"
        val conversation = "r3-$nonce"
        fun projectionView(projection: Any, source: List<ChatMessage>): List<*> =
            type.getMethod("view", List::class.java, String::class.java).invoke(projection, source, scope) as List<*>
        val save = store.javaClass.methods.single { it.name.startsWith("save") }
        val load = store.javaClass.methods.single { it.name.startsWith("load") }
        if (phase == "seed") {
            val projection = type.getConstructor(Properties::class.java).newInstance(Properties())
            type.getMethod("freeze", List::class.java, List::class.java, String::class.java)
                .invoke(projection, messages, slice, scope)
            save.invoke(store, conversation, projection)
            assertEquals(slice, projectionView(projection, messages))
            report.writeText(JSONObject().put("nonce", nonce).put("seedPid", Process.myPid())
                .put("uid", Process.myUid()).put("ready", true).toString())
            // Host records and kills this exact PID; seed intentionally cannot produce green JUnit.
            java.util.concurrent.CountDownLatch(1).await()
        } else {
            val checkpoint = JSONObject(report.readText())
            assertEquals(nonce, checkpoint.getString("nonce"))
            assertNotEquals(checkpoint.getInt("seedPid"), Process.myPid())
            val projection = load.invoke(store, conversation)
            assertEquals(slice, projectionView(projection, messages))
            val appended = messages + ChatMessage(id = "append", role = MessageRole.USER, content = "new")
            assertEquals(slice, projectionView(projection, appended).take(slice.size))
            val digest = MessageDigest.getInstance("SHA-256").digest(slice.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            checkpoint.put("verifyPid", Process.myPid()).put("sameProjection", true)
                .put("appendPrefixUnchanged", true).put("projectionHash", digest)
            assertTrue(isolated.deleteRecursively())
            checkpoint.put("isolatedFilesCleaned", true)
            report.writeText(checkpoint.toString(2))
        }
    }
}
