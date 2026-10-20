package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.core.data.entity.DynamicModuleEntity
import com.zero.v2.framework.chat.ContextBuilderConfig
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Real released Debian module and guarded tools; a local planner never calls cloud models. */
class DynamicMediaAcceptanceTest {
    @Test fun discoveryExportDynamicReadPublishAndToolContinuation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val id = "a8_acceptance_${System.currentTimeMillis()}"
        val source = """
            import zero_tool
            import pathlib
            import hashlib
            @zero_tool.tool
            def inspect_media(path:str):
                p = pathlib.Path(path).resolve()
                if p.parent != pathlib.Path('/root/zero/attachments'):
                    raise ValueError('fixture_path_rejected')
                b = p.read_bytes()
                return {'path':str(p),'sha256':hashlib.sha256(b).hexdigest(),'bytes':len(b)}
            zero_tool.serve()
        """.trimIndent() + "\n"
        val module = DynamicModuleEntity(id = id, source = source, createdAt = System.currentTimeMillis())
        val mcp = entry.mcp()
        val files = entry.files()
        val manager = entry.lifecycleManager()
        val cfg = entry.contextBuilder().config as ContextBuilderConfig
        val evidence = cfg.evidenceAutoInject
        val rows = JSONArray()
        val root = File(context.filesDir, "attachments")
        val model = Planner("dyn_${id}_inspect_media")
        assertTrue(entry.modelSelector().getAll().isEmpty())
        try {
            cfg.evidenceAutoInject = false
            assertTrue(provider.releaseManager().publish(id, source,
                ModuleReleaseManifest(sdkRange = ">=1.1,<2")).ok)
            assertNotNull(manager.safeCall(mcp, "acceptance-start") { mcp.onStart() })
            assertNotNull(manager.safeCall(files, "acceptance-start") { files.onStart() })
            entry.toolRegistrar().registerContributions(files)
            assertEquals(true, manager.safeCall(mcp, "acceptance-mount") { mcp.mountDynamicModule(module) })
            entry.modelSelector().register(model)
            entry.chatBot().initialize("isolated dynamic media acceptance")
            for ((asset, mime, type) in listOf(Triple("tone.m4a", "audio/mp4", AttachmentType.AUDIO),
                Triple("frames.mp4", "video/mp4", AttachmentType.VIDEO))) {
                val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("a8media/$asset").use { it.readBytes() }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val owned = !File(root, hash).exists()
                val file = AttachmentVault(root).save(bytes)
                try {
                    model.reset(file.toURI().toString(), mime)
                    entry.chatBot().loadHistory("isolated-$id-$asset", emptyList())
                    withTimeout(90_000) { entry.chatBot().sendMessage("inspect synthetic media", attachments = listOf(
                        Attachment(file.toURI().toString(), mime, type, route = MediaRoute.TOOLS)))
                        .collect(entry.chatBot()::applyStreamChunk) }
                    val history = entry.chatBot().chatHistory.value.messages
                    val tools = history.filter { it.role == MessageRole.TOOL }
                    assertEquals(4, tools.size)
                    assertFalse(tools.joinToString { it.content.orEmpty() }.contains("[错误:"))
                    assertTrue(tools.first().content.orEmpty().contains(model.dynamicName))
                    val inspected = JSONObject(tools[2].content)
                    assertEquals(hash, inspected.getString("sha256"))
                    assertEquals(bytes.size, inspected.getInt("bytes"))
                    val returned = tools[3].attachments.single()
                    assertEquals(mime, returned.mimeType)
                    assertEquals(type, returned.type)
                    assertArrayEquals(bytes, File(java.net.URI(returned.uri)).readBytes())
                    assertEquals("MEDIA_DONE", history.last().content)
                    assertEquals(1, model.prefixes.distinct().size)
                    assertTrue(model.requests.zipWithNext().all { (a, b) -> b.take(a.size) == a })
                    rows.put(JSONObject().put("mime", mime).put("sha256", hash).put("bytes", bytes.size)
                        .put("toolRounds", tools.size).put("stablePrefix", true).put("appendOnlyHistory", true))
                } finally { if (owned) file.delete() }
            }
            File(context.filesDir, "a8dynamic-media-report.json").writeText(JSONObject().put("module", id).put("cases", rows)
                .put("scope", "real_release_mount_mcp_discovery_export_publish_local_planner_not_semantic_or_cloud").toString(2))
        } finally {
            cfg.evidenceAutoInject = evidence
            entry.modelSelector().unregister(model.modelId)
            manager.safeCall(mcp, "acceptance-unmount") { mcp.unmountDynamicModule(module) }
            provider.database().dynamicModuleDao().delete(id)
            files.contributions().forEach { entry.toolRegistrar().unregister(it.definition.name) }
            entry.termux().supPost("/module/$id/stop", JSONObject())
        }
    }
    private class Planner(val dynamicName: String) : ILanguageModel {
        override val modelId = "isolated-dynamic-media"
        override val displayName = "isolated"
        override val contextWindow = 960000
        override val capabilities = setOf(ModelCapability.CHAT, ModelCapability.FUNCTION_CALLING)
        override val keyConfigured = true
        override val priority = 0
        override val enabled = true
        val requests = mutableListOf<List<ChatMessage>>()
        val prefixes = mutableListOf<Pair<String?, String?>>()
        private var reference = ""
        private var mime = ""
        fun reset(reference: String, mime: String) { this.reference = reference; this.mime = mime; requests.clear(); prefixes.clear() }
        override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("No cloud")
        override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) = flow {
            requests += messages.toList(); prefixes += systemPrompt to prefixVersion
            val arguments = JSONObject()
            val name = when (requests.size) {
                1 -> { arguments.put("query", dynamicName); "discover_tools" }
                2 -> { arguments.put("name", "attachment_export").put("arguments", JSONObject().put("attachment_id", reference)); "invoke_tool" }
                3 -> { val path = JSONObject(messages.last { it.role == MessageRole.TOOL }.content).getString("path")
                    arguments.put("name", dynamicName).put("arguments", JSONObject().put("path", path)); "invoke_tool" }
                4 -> { val path = JSONObject(messages.last { it.role == MessageRole.TOOL }.content).getString("path")
                    arguments.put("name", "attachment_publish").put("arguments", JSONObject().put("path", path).put("mime", mime)); "invoke_tool" }
                else -> null
            }
            if (name != null) emit(StreamChunk(toolCallDelta = ToolCallDelta(id = "media-${requests.size}", name = name, argumentsDelta = arguments.toString())))
            else emit(StreamChunk(contentDelta = "MEDIA_DONE"))
            emit(StreamChunk(isFinal = true))
        }
    }
}
