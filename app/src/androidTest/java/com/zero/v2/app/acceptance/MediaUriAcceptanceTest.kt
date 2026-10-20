package com.zero.v2.app.acceptance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.ConversationRepository
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MediaUriAcceptanceTest {
    @Test fun realContentImageSurvivesSourceRemovalAndNativeFailureIsExplicit() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        assertTrue(entry.modelSelector().getAll().isEmpty())
        val model = TextOnlyModel()
        entry.modelSelector().register(model)
        val root = File(context.filesDir, "attachments").also { it.mkdirs() }
        val source = File(root, "acceptance-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(UUID.randomUUID().hashCode() or 0xff000000.toInt())
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        val bytes = source.readBytes()
        var imported: File? = null
        var ownedImport = false
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val config = entry.contextBuilder().config as ContextBuilderConfig
        val evidence = config.evidenceAutoInject
        try {
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", source)
            assertEquals("content", contentUri.scheme)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            ownedImport = !File(root, digest).exists()
            val prepared = entry.attachments().prepare(Attachment(contentUri.toString(), "image/png", route = MediaRoute.NATIVE))
            imported = File(java.net.URI(prepared.uri))
            assertEquals(AttachmentType.IMAGE, prepared.type)
            assertEquals(MediaRoute.NATIVE, prepared.route)
            assertArrayEquals(bytes, imported.readBytes())
            assertTrue(prepared.base64Data!!.startsWith("data:image/png;base64,"))
            assertTrue(source.delete())
            val repo = ConversationRepository(db.conversationDao(), db.messageDao())
            val id = repo.createConversation("isolated actual image URI")
            val user = ChatMessage("actual-image-user", MessageRole.USER, "local image", attachments = listOf(prepared))
            repo.persistTurn(id, listOf(user), user.id)
            val stored = repo.toChatMessage(repo.getMessages(id).single())!!
            val restored = stored.attachments.single()
            assertNull(restored.base64Data)
            assertEquals(MediaRoute.NATIVE, restored.route)
            assertEquals(16, BitmapFactory.decodeFile(File(java.net.URI(restored.uri)).path).width)
            val shared = FileProvider.getUriForFile(context, "${context.packageName}.attachments", imported)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(shared)!!.use { it.readBytes() })
            config.evidenceAutoInject = false
            entry.chatBot().initialize("isolated image route")
            entry.chatBot().loadHistory(id, emptyList())
            val chunks = mutableListOf<StreamChunk>()
            entry.chatBot().sendMessage("native image requested", attachments = listOf(prepared)).collect { chunks += it; entry.chatBot().applyStreamChunk(it) }
            assertEquals("MEDIA_MODEL_UNAVAILABLE", chunks.last().errorCode)
            assertEquals(0, model.requests)
            val native = TextOnlyModel(true)
            entry.modelSelector().register(native)
            try {
                entry.chatBot().loadHistory(id, emptyList())
                entry.chatBot().sendMessage("native image", attachments = listOf(prepared)).collect(entry.chatBot()::applyStreamChunk)
                assertEquals(1, native.requests)
                assertTrue(native.received.single().base64Data!!.startsWith("data:image/png;base64,"))
                entry.chatBot().loadHistory(id, emptyList())
                entry.chatBot().sendMessage("tools image", attachments = listOf(prepared.copy(route = MediaRoute.TOOLS))).collect(entry.chatBot()::applyStreamChunk)
                assertEquals(1, model.requests)
                assertEquals(1, native.requests)
                assertEquals(MediaRoute.TOOLS, model.received.single().route)
            } finally { entry.modelSelector().unregister(native.modelId) }
            entry.modelSelector().unregister(model.modelId)
            entry.chatBot().loadHistory(id, emptyList())
            chunks.clear()
            entry.chatBot().sendMessage("no chat model").collect { chunks += it; entry.chatBot().applyStreamChunk(it) }
            assertEquals("CHAT_MODEL_UNAVAILABLE", chunks.last().errorCode)
        } finally {
            config.evidenceAutoInject = evidence
            entry.modelSelector().unregister(model.modelId)
            source.delete()
            if (ownedImport) imported?.delete()
            db.close()
        }
    }

    private class TextOnlyModel(private val image: Boolean = false) : ILanguageModel {
        override val modelId = if (image) "isolated-media-image" else "isolated-media-text"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING) +
            if (image) setOf(ModelCapability.IMAGE_INPUT) else emptySet()
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        var requests = 0
        var received = emptyList<Attachment>()
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No sync request")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            requests++
            received = messages.last { it.role == MessageRole.USER }.attachments
            emit(StreamChunk(contentDelta = "LOCAL", isFinal = true))
        }
    }
}
