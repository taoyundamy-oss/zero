package com.zero.v2.services.llm

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.zero.v2.core.common.AttachmentVault
import com.zero.v2.core.common.ChatMessage
import com.zero.v2.core.common.ContentLimits
import com.zero.v2.core.common.ReadOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Resolve persisted references only when a provider actually consumes image bytes. */
@Singleton
class StoredAttachmentReader @Inject constructor(@ApplicationContext private val context: Context) {
    private val vault = AttachmentVault(File(context.filesDir, "attachments"))

    fun restore(messages: List<ChatMessage>): List<ChatMessage> = messages.map { message ->
        message.copy(attachments = message.attachments.map { attachment ->
            if (!attachment.base64Data.isNullOrBlank()) return@map attachment
            if (message.role != com.zero.v2.core.common.MessageRole.USER ||
                attachment.type != com.zero.v2.core.common.AttachmentType.IMAGE ||
                attachment.route == com.zero.v2.core.common.MediaRoute.TOOLS) return@map attachment
            try {
                val uri = Uri.parse(attachment.uri)
                val bytes = if (uri.scheme == "file") {
                    vault.resolve(attachment.uri).inputStream().use(ContentLimits::readBounded)
                } else {
                    context.contentResolver.openInputStream(uri)?.use(ContentLimits::readBounded) ?: byteArrayOf()
                }
                if (ContentLimits.classify(attachment.mimeType, bytes) !is ReadOutcome.Ok) attachment
                else attachment.copy(base64Data = "data:${attachment.mimeType};base64," +
                    Base64.encodeToString(bytes, Base64.NO_WRAP))
            } catch (_: java.io.IOException) {
                attachment
            } catch (_: SecurityException) {
                attachment
            } catch (_: IllegalArgumentException) {
                attachment
            }
        })
    }
}
