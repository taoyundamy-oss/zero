package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentType
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.MediaRoute
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ProviderContentState
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.common.ToolCall
import com.zero.v2.services.llm.ChatGptPlanLanguageModel
import com.zero.v2.services.llm.ModelCapability
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Explicit opt-in. Ephemeral models, synthetic inputs, persistent four-request cap, no saved route changes. */
class ChatGptPlanAcceptanceTest {
    @Test fun accountRestoresAndSyntheticInferenceCompletes() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is ProviderAcceptanceRunner)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("chatGptPlanAcceptance") == "true")
        val context = inst.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val repair = args.getString("streamRepair") == "true"
        val output = File(context.filesDir, if (repair) "chatgpt-s3-repair.json" else "chatgpt-s3-acceptance.json")
        val journal = File(context.filesDir, "chatgpt-s3-attempts.json")
        check(!output.exists()) { "Do not automatically repeat cloud acceptance" }
        if (repair) {
            check(JSONObject(journal.readText()).getInt("attempts") == 1)
            check(File(context.filesDir, "chatgpt-s3-stream-probe.json").exists())
        } else check(!journal.exists())
        val rows = JSONArray()
        val cap = if (repair) 6 else 4
        val report = JSONObject().put("rows", rows).put("postCap", cap)
        fun flush() = output.writeText(report.toString(2))
        flush()
        try {
            val auth = entry.chatGptAuth()
            auth.reload()
            val account = requireNotNull(auth.state.value.active) { "User browser sign-in required" }
            check(auth.ready(account)) { "Plan scope required" }
            report.put("restoredAuthorizedAccount", true)
            val catalog = entry.chatGptCatalog().fetch(account)
            report.put("catalogModels", JSONArray(catalog.map { it.modelId }))
            val selected = requireNotNull(catalog.firstOrNull())
            report.put("selectedModel", selected.modelId)
            val profile = selected.copy(capabilities = setOf(ModelCapability.CHAT,
                ModelCapability.FUNCTION_CALLING, ModelCapability.IMAGE_INPUT, ModelCapability.VISION))
            val model = ChatGptPlanLanguageModel(profile, entry.llmClient(), auth, entry.profiles()).snapshotForTurn()
            var attempts = if (repair) 2 else 0
            suspend fun request(label: String, messages: List<ChatMessage>, functions: List<FunctionDefinition>): Reply {
                check(attempts < cap)
                attempts++
                journal.writeText(JSONObject().put("attempts", attempts).toString())
                val row = JSONObject().put("case", label).put("status", "started")
                rows.put(row); flush()
                val text = StringBuilder()
                val calls = mutableListOf<ToolCall>()
                var native: ProviderContentState? = null
                var usage: TokenUsage? = null
                var completed = false
                model.chatStream(messages, "Follow the synthetic test instructions exactly.", functions).collect { c ->
                    c.contentDelta?.let(text::append)
                    c.providerContent?.let { native = it }
                    c.tokenUsage?.let { usage = it }
                    c.toolCallDelta?.let { calls += ToolCall(requireNotNull(it.id),
                        requireNotNull(it.name), it.argumentsDelta.orEmpty()) }
                    if (c.isFinal) completed = true
                }
                check(completed)
                row.put("status", "completed").put("input", usage?.inputTokens)
                    .put("output", usage?.outputTokens).put("cached", usage?.cachedTokens)
                    .put("cacheObserved", usage?.cacheObservedInputTokens)
                    .put("nativeItems", native != null).put("toolCalls", calls.size)
                flush()
                return Reply(text.toString(), calls, native)
            }
            val text = request("text", listOf(ChatMessage(role = MessageRole.USER,
                content = "Reply with exactly SIWC_OK.")), emptyList())
            check(text.text.trim() == "SIWC_OK")
            val tools = listOf(FunctionDefinition("probe", "Return a synthetic verification word.",
                mapOf("type" to "object", "properties" to emptyMap<String, Any>())))
            val user = ChatMessage(id = "siwc-tool", role = MessageRole.USER,
                content = "Call probe once, then reply with exactly the word it returns.")
            val first = request("tool_call", listOf(user), tools)
            check(first.calls.size == 1 && first.calls.single().name == "probe")
            val nonce = "ZERO_" + java.util.UUID.randomUUID().toString().take(8)
            val second = request("tool_result", listOf(user, ChatMessage(role = MessageRole.ASSISTANT,
                content = first.text, toolCalls = first.calls, providerContent = first.native),
                ChatMessage(role = MessageRole.TOOL, toolCallId = first.calls.single().id, content = nonce)), tools)
            check(second.text.trim() == nonce && second.calls.isEmpty())
            val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.RED)
            val bytes = java.io.ByteArrayOutputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
            }
            bitmap.recycle()
            val image = "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val visual = request("image", listOf(ChatMessage(role = MessageRole.USER,
                content = "What color is this image? Reply with exactly RED, GREEN or BLUE.", attachments = listOf(
                    Attachment("synthetic:test", "image/png", AttachmentType.IMAGE, image, MediaRoute.NATIVE)))), emptyList())
            check(visual.text.trim() == "RED")
            report.put("passed", true)
        } catch (failure: Exception) {
            report.put("passed", false).put("errorClass", failure.javaClass.simpleName)
                .put("error", failure.message.orEmpty().take(300))
            throw failure
        } finally { flush() }
    }
    private data class Reply(val text: String, val calls: List<ToolCall>, val native: ProviderContentState?)
}
