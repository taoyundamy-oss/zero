package com.zero.v2.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.MediaAttachmentPolicy
import com.zero.v2.core.common.MediaRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Saved primitive references only: media bytes and credentials never enter the Activity state bundle. */
internal class ChatDraftState(private val saved: SavedStateHandle) {
    private val textState = MutableStateFlow(saved.get<String>("chat.draft.text").orEmpty())
    val text = textState.asStateFlow()
    private val attachmentState = MutableStateFlow(restoreAttachment())
    val attachment = attachmentState.asStateFlow()
    var conversationId: String
        get() = saved.get<String>("chat.conversation").orEmpty()
        set(value) { saved["chat.conversation"] = value }

    fun setText(value: String) { textState.value = value; saved["chat.draft.text"] = value }
    fun setAttachment(value: Attachment?) {
        attachmentState.value = value
        saved["chat.draft.uri"] = value?.uri
        saved["chat.draft.mime"] = value?.mimeType
        saved["chat.draft.route"] = value?.route?.name
    }
    private fun restoreAttachment(): Attachment? {
        val uri = saved.get<String>("chat.draft.uri") ?: return null
        val mime = saved.get<String>("chat.draft.mime") ?: return null
        val route = MediaRoute.entries.firstOrNull { it.name == saved.get<String>("chat.draft.route") } ?: MediaRoute.AUTO
        return Attachment(uri, mime, type = MediaAttachmentPolicy.type(mime), route = route)
    }
}
