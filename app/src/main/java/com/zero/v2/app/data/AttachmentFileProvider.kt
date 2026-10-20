package com.zero.v2.app.data

import android.net.Uri
import androidx.core.content.FileProvider
import java.util.Locale

/** View URI metadata mirrors the caller MIME; bytes and access remain guarded by FileProvider. */
class AttachmentFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        // Validate/canonicalize the configured file path before considering advisory metadata.
        val fallback = super.getType(uri)
        val mime = uri.getQueryParameter(MIME_PARAMETER)
        return if (mime != null && MIME_PATTERN.matches(mime)) mime.lowercase(Locale.ROOT) else fallback
    }

    companion object {
        const val MIME_PARAMETER = "zero_mime"
        private val MIME_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,63}/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,63}")
    }
}
