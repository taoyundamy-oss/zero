package com.zero.v2.app.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.zero.v2.app.ui.theme.ZeroShapes
import com.zero.v2.core.common.Attachment
import com.zero.v2.core.common.AttachmentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared by the composer and persisted messages; large photos are decoded as thumbnails. */
@Composable
fun AttachmentTile(attachment: Attachment, onRemove: (() -> Unit)? = null) {
    val context = LocalContext.current
    var audioPreview by remember(attachment.uri) { mutableStateOf(false) }
    if (audioPreview) AttachmentAudioDialog(attachment) { audioPreview = false }
    var preview by remember(attachment.uri, attachment.type) { mutableStateOf<Pair<Boolean, ImageBitmap?>>(false to null) }
    var openError by remember(attachment.uri, attachment.mimeType) { mutableStateOf<String?>(null) }
    LaunchedEffect(attachment.uri, attachment.type) {
        if (attachment.type != AttachmentType.IMAGE) {
            preview = true to null
            return@LaunchedEffect
        }
        preview = withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(attachment.uri)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
                }
                true to context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
                }
            } catch (_: java.io.IOException) {
                true to null
            } catch (_: SecurityException) {
                true to null
            }
        }
    }
    val label = when (attachment.type) {
        AttachmentType.IMAGE -> "图片"
        AttachmentType.AUDIO -> "音频"
        AttachmentType.VIDEO -> "视频"
        AttachmentType.FILE -> "文件"
    }
    Surface(modifier = Modifier.clickable { openError = openAttachment(context, attachment) },
        shape = ZeroShapes.card, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            preview.second?.let {
                Image(it, "图片预览", Modifier.size(64.dp), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f)) {
                Text("${label}附件", style = MaterialTheme.typography.labelLarge)
                val caption = when {
                    !preview.first -> "正在加载预览"
                    attachment.type == AttachmentType.IMAGE && preview.second == null -> "预览不可用 · 点击尝试打开"
                    else -> "点击打开 · ${attachment.mimeType}"
                }
                Text(caption, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                openError?.let { message ->
                    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (attachment.type == AttachmentType.AUDIO) IconButton(onClick = { audioPreview = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.PlayArrow, "预览音频")
            }
            if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, "移除附件")
            }
        }
    }
}

private fun openAttachment(context: android.content.Context, attachment: Attachment): String? =
    try {
        context.startActivity(attachmentViewIntent(context, attachment))
        null
    } catch (_: Exception) {
        "无法打开附件：文件不可用或没有兼容的查看应用。可再次点击重试或移除附件。"
    }

internal fun attachmentViewIntent(context: android.content.Context, attachment: Attachment): android.content.Intent {
    val mime = attachment.mimeType.lowercase(java.util.Locale.ROOT)
    val original = Uri.parse(attachment.uri)
    val uri = if (original.scheme == "file") {
        val file = com.zero.v2.core.common.AttachmentVault(java.io.File(context.filesDir, "attachments"))
            .resolve(attachment.uri)
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
            .buildUpon().appendQueryParameter(com.zero.v2.app.data.AttachmentFileProvider.MIME_PARAMETER, mime).build()
    } else original
    return android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
