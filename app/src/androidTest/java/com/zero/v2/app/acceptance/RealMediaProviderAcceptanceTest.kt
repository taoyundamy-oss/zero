package com.zero.v2.app.acceptance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.*
import com.zero.v2.services.llm.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic media only; no persisted profile changes, no personal conversation or key export. */
class RealMediaProviderAcceptanceTest {
    @Test fun configuredImageVideoAndSpeechRequests() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        provider.chatProfiles().refreshProfiles()
        val profiles = provider.profiles().profiles.value
        val report = JSONObject().put("inventory", JSONArray().apply {
            profiles.filter { it.kind in setOf(LlmProfile.Kind.CHAT, LlmProfile.Kind.ASR) }.forEach {
                put(JSONObject().put("id", it.id).put("model", it.modelId).put("enabled", it.enabled)
                    .put("keyConfigured", provider.llmClient().resolveApiKey(it) != null)
                    .put("capabilities", JSONArray(it.capabilities.map { c -> c.name })))
            }
        })
        val output = File(context.filesDir, "a8-real-media-report.json")
        output.writeText(report.toString(2))
        if (InstrumentationRegistry.getArguments().getString("runRealMedia") != "true") return@runBlocking
        val mediaCase = InstrumentationRegistry.getArguments().getString("mediaCase") ?: "all"
        require(mediaCase in setOf("all", "video", "image_audio"))
        val journal = File(context.filesDir, "a8-media-provider-journal.json")
        suspend fun reserve() {
            assertEquals(com.zero.v2.services.agent.BudgetGuard.Verdict.OK, provider.budget().preCheck("a8-media-acceptance"))
            val state = if (journal.exists()) JSONObject(journal.readText()) else JSONObject().put("attempts", 0)
            val attempts = state.getInt("attempts")
            check(attempts < 6) { "A8 media acceptance six-attempt safety cap reached" }
            state.put("attempts", attempts + 1)
            journal.writeText(state.toString(2))
        }
        val selected = entry.modelSelector().select(ModelRequirements(input = setOf(InputModality.TEXT, InputModality.IMAGE))).model
        report.put("selectedImage", selected?.modelId ?: JSONObject.NULL)
        val profile = profiles.firstOrNull { it.modelId == selected?.modelId && it.enabled && it.kind == LlmProfile.Kind.CHAT }
        val rows = JSONArray()
        report.put("cases", rows)
        val root = File(context.filesDir, "attachments")
        val failures = mutableListOf<String>()
        suspend fun case(name: String, bytes: ByteArray, mime: String, type: AttachmentType, prompt: String, accept: (String) -> Boolean) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val owned = !File(root, hash).exists()
            val file = AttachmentVault(root).save(bytes)
            val row = JSONObject().put("case", name).put("inputSha256", hash)
            try {
                if (type != AttachmentType.AUDIO) check(profile != null) { "No configured image-input CHAT profile" }
                else check(profiles.any { it.enabled && it.kind == LlmProfile.Kind.ASR && provider.llmClient().resolveApiKey(it) != null }) { "No configured ASR profile" }
                reserve()
                val text = withTimeout(90_000) { provider.mediaAnalysis().analyze(Attachment(file.toURI().toString(), mime, type), prompt) }
                val ok = accept(text)
                row.put("response", text.take(300)).put("passed", ok)
                if (!ok) failures += "$name semantic mismatch"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val code = Regex("HTTP\\s+(\\d{3})").find(e.message.orEmpty())?.groupValues?.get(1)
                row.put("passed", false).put("errorClass", e.javaClass.simpleName).put("httpStatus", code ?: JSONObject.NULL)
                failures += "$name ${e.javaClass.simpleName} HTTP=${code ?: "unknown"}"
            } finally {
                if (owned) file.delete()
                rows.put(row); output.writeText(report.toString(2))
            }
        }
        val bounded = profile?.let { selectedProfile -> object : ILanguageModel {
            override val modelId = selectedProfile.modelId
            override val displayName = selectedProfile.displayName
            override val contextWindow = selectedProfile.contextWindow
            override val capabilities = selectedProfile.capabilities
            override val keyConfigured = true
            override val priority = selectedProfile.priority
            override val enabled = true
            override suspend fun chatSync(userMessage: String, systemPrompt: String?) = error("Stream only")
            override fun chatStream(messages: List<ChatMessage>, systemPrompt: String?, functions: List<FunctionDefinition>, prefixVersion: String?) =
                provider.llmClient().chatStream(selectedProfile.copy(maxTokens = 256, enableThinking = false), messages, systemPrompt, functions,
                    prefixVersion = prefixVersion)
        } }
        if (selected != null && bounded != null) { entry.modelSelector().unregister(selected.modelId); entry.modelSelector().register(bounded) }
        try {
            val bitmap = Bitmap.createBitmap(600, 250, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("73142", 40f, 175f, Paint().apply { color = Color.BLACK; textSize = 140f; isAntiAlias = true }) }
            val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            if (mediaCase != "video") case("image", png, "image/png", AttachmentType.IMAGE, "只读取图片中的五位数字。", { it.contains("73142") })
            // A rejected image route is not retried through another provider or charged again for video.
            if (failures.isEmpty() && mediaCase != "image_audio") case("video", instrumentation.context.assets.open("a8media/viewer.mp4").use { it.readBytes() },
                "video/mp4", AttachmentType.VIDEO, "说明三个采样画面中从左到右的三条色带颜色。", { it.contains("红") && it.contains("绿") && it.contains("蓝") })
            if (mediaCase != "video") case("audio", instrumentation.context.assets.open("a8media/spoken-code.wav").use { it.readBytes() },
                "audio/wav", AttachmentType.AUDIO, "", { it.replace(" ", "").contains("73142") || it.contains("七三一四二") })
        } finally {
            if (selected != null) { entry.modelSelector().unregister(selected.modelId); entry.modelSelector().register(selected) }
            report.put("attempts", if (journal.exists()) JSONObject(journal.readText()).getInt("attempts") else 0)
            report.put("scope", "real_configured_providers_synthetic_media_bounded_image_video_output_not_chat_UI_or_cache_gate")
            output.writeText(report.toString(2))
        }
        assertTrue(failures.joinToString(), failures.isEmpty())
    }
}
