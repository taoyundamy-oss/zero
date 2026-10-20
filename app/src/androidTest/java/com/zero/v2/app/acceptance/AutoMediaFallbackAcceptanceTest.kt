package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AutoMediaFallbackAcceptanceTest {
    @Test fun unreadableAutoImageHasExplicitFallbackAndUnchangedUserText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val cfg = entry.contextBuilder().config as ContextBuilderConfig
        val evidence = cfg.evidenceAutoInject
        val model = LocalText()
        assertTrue(entry.modelSelector().getAll().isEmpty())
        entry.modelSelector().register(model)
        try {
            cfg.evidenceAutoInject = false
            entry.chatBot().initialize("isolated AUTO fallback")
            entry.chatBot().loadHistory("isolated-auto-${java.util.UUID.randomUUID()}", emptyList())
            val ref = File(context.filesDir, "attachments/" + "f".repeat(64) + java.util.UUID.randomUUID()).toURI().toString()
            val text = "请识别测试附件"
            entry.chatBot().sendMessage(text, attachments = listOf(Attachment(ref, "image/png", route = MediaRoute.AUTO)))
                .collect(entry.chatBot()::applyStreamChunk)
            val history = entry.chatBot().chatHistory.value.messages
            assertEquals(text, history.first { it.role == MessageRole.USER }.content)
            assertTrue("AUTO unreadable image silently skipped fallback", model.received.any {
                it.content.orEmpty().contains("[媒体不可用 attachmentId=$ref") })
            assertTrue(model.received.any { it.content.orEmpty().contains("attachment_export") })
            assertEquals("LOCAL", history.last().content)
            val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val root = File(context.filesDir, "attachments")
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val owned = !File(root, hash).exists()
            val file = AttachmentVault(root).save(bytes)
            val visionEnabled = entry.vision().isEnabled
            var calls = 0
            val vision = FunctionDefinition("vision_analyze", "local vision contract", mapOf("type" to "object"))
            try {
                entry.toolRegistrar().register(vision, "chatbot") { args ->
                    calls++
                    val data = (args["image_data"] as String).substringAfter("base64,")
                    assertArrayEquals(bytes, android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                    FunctionResult.success("LOCAL_OCR")
                }
                entry.chatBot().loadHistory("isolated-auto-readable", emptyList())
                entry.chatBot().sendMessage(text, attachments = listOf(Attachment(file.toURI().toString(), "image/png", route = MediaRoute.AUTO)))
                    .collect(entry.chatBot()::applyStreamChunk)
                assertEquals(1, calls)
                assertTrue(model.received.any { it.content.orEmpty().contains("LOCAL_OCR") })
                // The real lifecycle guard must reject an unavailable owner, without invoking its handler.
                entry.toolRegistrar().register(vision, "vision_analyze") { calls++; FunctionResult.success("MUST_NOT_RUN") }
                entry.vision().isEnabled = false
                entry.chatBot().loadHistory("isolated-auto-unavailable-owner", emptyList())
                entry.chatBot().sendMessage(text, attachments = listOf(Attachment(file.toURI().toString(), "image/png", route = MediaRoute.AUTO)))
                    .collect(entry.chatBot()::applyStreamChunk)
                assertEquals(1, calls)
                assertTrue(model.received.any { it.content.orEmpty().contains("[媒体不可用") })
                File(context.filesDir, "a8auto-report.json").writeText("""{"missingVisible":true,"restoredBytes":true,"unavailableOwnerBlocked":true,"cloudRequests":0,"scope":"production_chat_reader_dispatch_guard_with_local_vision_contract_not_real_provider"}""")
            } finally {
                entry.vision().isEnabled = visionEnabled
                entry.toolRegistrar().unregister(vision.name)
                if (owned) file.delete()
            }
        } finally {
            cfg.evidenceAutoInject = evidence
            entry.modelSelector().unregister(model.modelId)
        }
    }
    private class LocalText : ILanguageModel {
        override val modelId = "isolated-auto-fallback"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        var received = emptyList<ChatMessage>()
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No cloud")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            received = messages.toList()
            emit(StreamChunk(contentDelta = "LOCAL", isFinal = true))
        }
    }
}
