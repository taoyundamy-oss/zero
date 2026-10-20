package com.zero.v2.app.acceptance

import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.framework.chat.ChatBotModuleConfig
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.ConnectionAuth
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.ProfileLanguageModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real Qwen tools and media bytes; no claim of visual/ASR/TTS semantic analysis. */
class LocalMediaAgentAcceptanceTest {
    @Test fun realQwenReturnsImageAudioVideoReferencesThroughMediaTools() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowLocalMedia") == "true")
        assertTrue(inst is ProviderAcceptanceRunner)
        val context = inst.targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        assertTrue(entry.modelSelector().getAll().isEmpty())
        val profile = LlmProfile.localQwen("http://127.0.0.1:8080/v1", "local-qwen")
            .copy(id = "isolated-a10-media", authMode = ConnectionAuth.NONE, maxTokens = 512)
        assertNull(provider.profiles().get(profile.id))
        val model = ProfileLanguageModel(profile.id, profile, provider.llmClient(), provider.profiles())
        val cfg = entry.contextBuilder().config as ContextBuilderConfig
        val chatCfg = entry.chatBot().config as ChatBotModuleConfig
        val oldEvidence = cfg.evidenceAutoInject
        val oldRounds = chatCfg.maxFcRounds
        val rows = JSONArray()
        val report = JSONObject().put("accepted", false).put("cloudRequests", 0)
            .put("scope", "real Qwen tools + image/audio/video byte export and publish; not semantic analysis")
            .put("cases", rows)
        val root = File(context.filesDir, "attachments").also { it.mkdirs() }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff3183d8.toInt())
        val png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val cases = listOf(
            Triple(png, "image/png", AttachmentType.IMAGE),
            Triple(inst.context.assets.open("a8media/tone.m4a").use { it.readBytes() }, "audio/mp4", AttachmentType.AUDIO),
            Triple(inst.context.assets.open("a8media/frames.mp4").use { it.readBytes() }, "video/mp4", AttachmentType.VIDEO),
        )
        val definition = FunctionDefinition("a10_media_roundtrip", "导出并回传本轮附件，返回媒体引用和随机口令；无语义识别",
            mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
        entry.modelSelector().register(model)
        try {
            cfg.evidenceAutoInject = false
            chatCfg.maxFcRounds = 3
            entry.chatBot().initialize("isolated local media acceptance")
            withTimeout(180_000) {
                for ((bytes, mime, kind) in cases) {
                    val extension = when (kind) {
                        AttachmentType.IMAGE -> "png"
                        AttachmentType.AUDIO -> "m4a"
                        else -> "mp4"
                    }
                    val source = File(root, "acceptance-${UUID.randomUUID()}.$extension").also { it.writeBytes(bytes) }
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    val owned = !File(root, digest).exists()
                    val nonce = "ZERO_" + UUID.randomUUID().toString().take(8)
                    var executions = 0
                    try {
                        val input = FileProvider.getUriForFile(context, "${context.packageName}.attachments", source)
                        val prepared = entry.attachments().prepare(Attachment(input.toString(), mime, kind, route = MediaRoute.TOOLS))
                        assertEquals(MediaRoute.TOOLS, prepared.route)
                        entry.toolRegistrar().register(definition, "chatbot") {
                            executions++
                            val exported = entry.attachments().export(prepared.uri)
                            check(exported.success) { "media_export_failed" }
                            val path = JSONObject(exported.data).getString("path")
                            val published = entry.attachments().publish(path, mime)
                            check(published.success) { "media_publish_failed" }
                            published.copy(data = JSONObject().put("message", published.data).put("nonce", nonce).toString())
                        }
                        entry.chatBot().loadHistory("isolated-media-${UUID.randomUUID()}", emptyList())
                        val chunks = mutableListOf<StreamChunk>()
                        entry.chatBot().sendMessage("请先discover_tools发现a10_media_roundtrip，再invoke_tool执行一次。最后仅回复工具返回的nonce，不要猜测或再次执行。",
                            attachments = listOf(prepared)).collect { chunks += it; entry.chatBot().applyStreamChunk(it) }
                        assertFalse(chunks.any { it.errorCode != null })
                        assertEquals(ChatState.IDLE, entry.chatBot().chatState.value)
                        assertEquals(1, executions)
                        assertTrue(chunks.joinToString("") { it.contentDelta.orEmpty() }.contains(nonce))
                        val returned = entry.chatBot().chatHistory.value.messages.filter { it.role == MessageRole.TOOL }
                            .flatMap { it.attachments }.single()
                        assertEquals(kind, returned.type)
                        assertEquals(mime, returned.mimeType)
                        assertArrayEquals(bytes, File(java.net.URI(returned.uri)).readBytes())
                        rows.put(JSONObject().put("type", kind.name).put("bytes", bytes.size).put("sha256", digest)
                            .put("executions", executions).put("toolMediaReferenceReturned", true))
                    } finally {
                        entry.toolRegistrar().unregister(definition.name)
                        source.delete()
                        if (owned) File(root, digest).delete()
                    }
                }
            }
            report.put("accepted", true)
        } finally {
            File(context.filesDir, "a10-local-media.json").writeText(report.toString(2))
            cfg.evidenceAutoInject = oldEvidence
            chatCfg.maxFcRounds = oldRounds
            entry.modelSelector().unregister(profile.id)
        }
    }
}
