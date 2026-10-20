package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real decoder/service, local model: analysis must not turn transient frames into orphan vault files. */
class MediaTemporaryFramesAcceptanceTest {
    @Test fun sampledFramesAreReadableWithoutGrowingDurableVault() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val root = File(context.filesDir, "attachments")
        val bytes = instrumentation.context.assets.open("a8media/temporary-frames.mp4").use { it.readBytes() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val owned = !File(root, hash).exists()
        val file = AttachmentVault(root).save(bytes)
        val before = root.listFiles().orEmpty().map { it.name }.toSet()
        val model = LocalImages()
        assertTrue(entry.modelSelector().getAll().isEmpty())
        entry.modelSelector().register(model)
        try {
            assertEquals("LOCAL_FRAMES", entry.mediaAnalysis().analyze(Attachment(file.toURI().toString(), "video/mp4", AttachmentType.VIDEO), "sample"))
            assertEquals(3, model.images.size)
            model.images.forEach {
                val data = android.util.Base64.decode(it.base64Data!!.substringAfter("base64,"), android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                assertNotNull(bitmap); bitmap.recycle()
            }
            val added = root.listFiles().orEmpty().map { it.name }.toSet() - before
            assertTrue("Analysis leaked durable frame files: ${added.size}", added.isEmpty())
            model.mode = "error"
            val attachment = Attachment(file.toURI().toString(), "video/mp4", AttachmentType.VIDEO)
            val failure = entry.mediaAnalysis().prepareBlock(listOf(attachment), "sample")
            assertTrue(failure.orEmpty().contains("处理失败"))
            assertTrue(failure.orEmpty().contains("attachment_export"))
            model.mode = "cancel"
            try { entry.mediaAnalysis().prepareBlock(listOf(attachment), "sample"); fail("Cancellation swallowed") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(before, root.listFiles().orEmpty().map { it.name }.toSet())
            File(context.filesDir, "a8-temporary-frames-report.json").writeText("""{"sampledFrames":3,"allDecoded":true,"vaultGrowth":0,"failureVisible":true,"cancellationPropagated":true,"cloudRequests":0}""")
        } finally {
            entry.modelSelector().unregister(model.modelId)
            // Only frame references emitted by this invocation, never arbitrary directory differences.
            model.images.filter { it.uri.startsWith("file:") }.forEach {
                val frame = AttachmentVault(root).resolve(it.uri)
                if (frame.name !in before) frame.delete()
            }
            if (owned) file.delete()
        }
    }
    private class LocalImages : ILanguageModel {
        override val modelId = "isolated-temporary-frames"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.IMAGE_INPUT)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        var images = emptyList<Attachment>()
        var mode = "success"
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("Local only")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            images = messages.last().attachments
            if (mode == "error") throw java.io.IOException("fixture_failure")
            if (mode == "cancel") throw kotlinx.coroutines.CancellationException("fixture_cancel")
            emit(StreamChunk(contentDelta = "LOCAL_FRAMES", isFinal = true))
        }
    }
}
