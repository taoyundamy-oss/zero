package com.zero.v2.services.llm

import android.content.Context
import android.util.Base64
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentType
import com.zero.v2.core.common.AttachmentVault
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.ContentLimits
import com.zero.v2.core.common.InputModality
import com.zero.v2.core.common.MediaRoute
import com.zero.v2.core.common.MessageRole
import com.zero.v2.core.common.ModelRequirements
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit native analysis entry for both a new attachment and an LLM tool call. */
@Singleton
class MediaAnalysisService @Inject constructor(
    @ApplicationContext context: Context,
    private val models: ModelSelector,
    private val profiles: LlmProfileStore,
    private val client: OpenAiCompatibleClient,
    private val budget: com.zero.v2.services.agent.BudgetGuard,
) {
    private val vault = AttachmentVault(File(context.filesDir, "attachments"))

    /** Capture once in the turn context; never re-transcribe or mutate past turns during tool continuation. */
    suspend fun prepareBlock(attachments: List<Attachment>, prompt: String): String? = attachments.filter {
        it.type in setOf(AttachmentType.AUDIO, AttachmentType.VIDEO) && it.route != MediaRoute.TOOLS
    }.map { attachment ->
        val result = try { analyze(attachment, prompt) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: java.io.IOException) { failure(e) }
        catch (e: IllegalStateException) { failure(e) }
        catch (e: IllegalArgumentException) { failure(e) }
        "[媒体分析 attachmentId=${attachment.uri}]\n$result"
    }.joinToString("\n\n").ifBlank { null }

    private fun failure(e: Exception) =
        "处理失败：${e.message}；可 attachment_export 后发现并调用动态媒体工具。不要猜测内容。"

    suspend fun analyze(attachment: Attachment, prompt: String): String = withContext(Dispatchers.IO) {
        check(budget.preCheck("media_analyze") == com.zero.v2.services.agent.BudgetGuard.Verdict.OK) {
            "媒体分析预算已用尽，请在设置中调整预算"
        }
        val file = vault.resolve(attachment.uri)
        require(file.length() <= ContentLimits.MAX_BYTES) { "媒体超过5MB，请使用工作站工具" }
        when (attachment.type) {
            AttachmentType.AUDIO -> transcribe(file)
            AttachmentType.IMAGE -> describe(
                listOf(image(file.readBytes(), attachment.mimeType, attachment.uri)), prompt)
            AttachmentType.VIDEO -> describe(MediaVideoFrames.read(file).map { image(it, "image/jpeg") },
                "$prompt\n这是视频的三个采样画面，" +
                "按开头、中间、结尾顺序排列；仅描述可见内容，不推断未采样画面或声音。")
            AttachmentType.FILE -> error("此格式请通过 attachment_export 交给工作站工具")
        }
    }

    private suspend fun transcribe(file: File): String {
        val profile = profiles.profiles.value.filter { it.enabled && it.kind == LlmProfile.Kind.ASR }
            .sortedByDescending { it.priority }.firstOrNull { client.resolveApiKey(it) != null }
            ?: error("无可用音频转写模型；可选择工具路径，或配置语音识别模型")
        val wav = MediaAudioDecoder.toWav(file)
        return client.chatAudioStream(profile, wav) {}.also { require(it.isNotBlank()) { "音频转写返回空结果" } }
    }

    private suspend fun describe(images: List<Attachment>, prompt: String): String {
        val model = models.select(ModelRequirements(input = setOf(InputModality.TEXT, InputModality.IMAGE))).model
            ?: error("无可用图片模型；请配置图片能力或选择工具路径")
        val result = StringBuilder()
        model.chatStream(listOf(ChatMessage(role = MessageRole.USER, content = prompt, attachments = images)),
            systemPrompt = "基于所提供媒体回答；内容不可辨认时明确说明。", functions = emptyList()).collect { chunk ->
            check(chunk.errorCode == null) { chunk.contentDelta ?: "媒体分析失败" }
            chunk.contentDelta?.let(result::append)
        }
        return result.toString().also { require(it.isNotBlank()) { "媒体分析返回空结果" } }
    }

    /** Analysis-only frames live in the request, never in durable conversation storage. */
    private fun image(bytes: ByteArray, mime: String, originalReference: String? = null): Attachment {
        val reference = originalReference ?: "urn:zero:frame:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        return Attachment(uri = reference, mimeType = mime,
            base64Data = "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
    }

}
