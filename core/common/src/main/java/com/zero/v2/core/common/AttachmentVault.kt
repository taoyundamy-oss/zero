package com.zero.v2.core.common

import java.io.File
import java.security.MessageDigest

/** Durable, content-addressed attachments. Only files owned by this vault can be exported. */
class AttachmentVault(private val root: File) {
    @Synchronized
    fun save(bytes: ByteArray): File {
        require(bytes.isNotEmpty() && bytes.size <= ContentLimits.MAX_BYTES) { "attachment_size_invalid" }
        check(root.isDirectory || root.mkdirs()) { "attachment_directory_unavailable" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val target = File(root, digest)
        if (!target.exists()) {
            val staging = File.createTempFile("attachment-", ".tmp", root)
            try {
                staging.outputStream().use { it.write(bytes) }
                check(staging.renameTo(target)) { "attachment_publish_failed" }
            } finally {
                staging.delete()
            }
        }
        return target
    }

    fun resolve(uri: String): File {
        val parsed = java.net.URI(uri)
        require(parsed.scheme == "file") { "attachment_not_imported" }
        val file = File(parsed).canonicalFile
        require(file.parentFile == root.canonicalFile && file.name.matches(Regex("[0-9a-f]{64}"))) {
            "attachment_outside_vault"
        }
        require(file.isFile) { "attachment_missing" }
        return file
    }
}
