package com.zero.v2.app.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentVault
import com.zero.v2.core.common.ContentLimits
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.ReadOutcome
import com.zero.v2.services.termux.TermuxBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val termux: TermuxBridge,
) {
    private val vault = AttachmentVault(File(context.filesDir, "attachments"))

    suspend fun prepare(attachment: Attachment): Attachment = withContext(Dispatchers.IO) {
        val uri = Uri.parse(attachment.uri)
        val declaredMime = context.contentResolver.getType(uri) ?: attachment.mimeType
        val bytes = if (uri.scheme == "file") {
            vault.resolve(attachment.uri).inputStream().use(ContentLimits::readBounded)
        } else {
            context.contentResolver.openInputStream(uri)?.use(ContentLimits::readBounded) ?: byteArrayOf()
        }
        val mime = com.zero.v2.core.common.MediaAttachmentPolicy.mimeForImport(declaredMime, bytes)
        com.zero.v2.core.common.MediaAttachmentPolicy.validate(mime, bytes)
        val saved = vault.save(bytes)
        attachment.copy(
            uri = saved.toURI().toString(), mimeType = mime,
            type = com.zero.v2.core.common.MediaAttachmentPolicy.type(mime),
            base64Data = if (mime.startsWith("image/"))
                "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}" else null,
        )
    }

    suspend fun export(attachmentId: String): FunctionResult = withContext(Dispatchers.IO) {
        val file = vault.resolve(attachmentId)
        if (!termux.ensureSupervisor()) return@withContext FunctionResult.failure("Termux 工作站不可用，请检查工作站状态")
        val path = "/root/zero/attachments/${file.name}"
        val bytes = file.inputStream().use(ContentLimits::readBounded)
        require(bytes.size <= ContentLimits.MAX_BYTES) { "attachment_oversize" }
        val response = termux.supPostRaw("/file/upload?path=${URLEncoder.encode(path, "UTF-8")}", bytes)
        if (response?.optBoolean("ok") != true) {
            return@withContext FunctionResult.failure("附件导出失败：${response?.optString("error") ?: "工作站无响应"}")
        }
        FunctionResult.success(JSONObject().put("path", path).put("attachment_id", attachmentId)
            .put("bytes", bytes.size).toString())
    }

    suspend fun publish(path: String, mime: String): FunctionResult = withContext(Dispatchers.IO) {
        if (!termux.ensureSupervisor()) return@withContext FunctionResult.failure("工作站不可用")
        val bytes = termux.readMediaFile(path) ?: return@withContext FunctionResult.failure("无法读取媒体文件")
        com.zero.v2.core.common.MediaAttachmentPolicy.validate(mime, bytes)
        val uri = vault.save(bytes).toURI().toString()
        FunctionResult.success("媒体已保存，可在对话中打开").copy(mediaRefs = listOf(com.zero.v2.core.common.MediaRef(
            uri, mime, com.zero.v2.core.common.MediaKind.valueOf(com.zero.v2.core.common.MediaAttachmentPolicy.type(mime).name))))
    }
}
