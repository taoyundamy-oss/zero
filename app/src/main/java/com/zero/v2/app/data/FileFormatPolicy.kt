package com.zero.v2.app.data

/** 文件工具支持的格式边界，和文件读写编排解耦。 */
internal object FileFormatPolicy {
    private val officeReadable = setOf("docx", "xlsx", "pptx", "pdf")
    private val documentWritable = setOf("docx", "xlsx", "pdf")

    fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun resolveDocumentExtension(fileName: String, requestedFormat: String): String =
        if (fileName.contains('.')) extension(fileName) else requestedFormat

    fun canReadOffice(extension: String): Boolean = extension in officeReadable
    fun canWriteDocument(extension: String): Boolean = extension in documentWritable
}
