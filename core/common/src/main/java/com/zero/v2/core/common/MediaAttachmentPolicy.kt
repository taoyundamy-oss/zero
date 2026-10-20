package com.zero.v2.core.common

/** Shared import contract. Unknown formats stay files and are never advertised as images. */
object MediaAttachmentPolicy {
    /** Some Android providers label M4A as MPEG audio; reconcile only recognizable MP4 headers. */
    fun mimeForImport(mime: String, bytes: ByteArray): String {
        if (!mime.equals("audio/mpeg", ignoreCase = true) || bytes.size < 12) return mime
        val box = bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII)
        val brand = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
        return if (box == "ftyp" && brand in setOf("M4A ", "M4B ", "isom", "iso2", "mp41", "mp42")) "audio/mp4" else mime
    }

    fun type(mime: String): AttachmentType = when {
        mime.startsWith("image/", ignoreCase = true) -> AttachmentType.IMAGE
        mime.startsWith("audio/", ignoreCase = true) -> AttachmentType.AUDIO
        mime.startsWith("video/", ignoreCase = true) -> AttachmentType.VIDEO
        else -> AttachmentType.FILE
    }

    fun validate(mime: String, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= ContentLimits.MAX_BYTES) { "附件为空或超过5MB" }
        require(type(mime) != AttachmentType.FILE) { "请选择图片、音频或视频文件" }
    }
}
