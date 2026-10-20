package com.zero.v2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFormatPolicyTest {
    @Test
    fun resolvesCaseInsensitiveExtensions() {
        assertEquals("docx", FileFormatPolicy.extension("report.DOCX"))
        assertEquals("pdf", FileFormatPolicy.resolveDocumentExtension("report", "pdf"))
        assertEquals("xlsx", FileFormatPolicy.resolveDocumentExtension("report.XLSX", "pdf"))
    }

    @Test
    fun keepsReadAndWriteFormatBoundaries() {
        assertTrue(FileFormatPolicy.canReadOffice("pptx"))
        assertTrue(FileFormatPolicy.canWriteDocument("docx"))
        assertFalse(FileFormatPolicy.canWriteDocument("pptx"))
    }
}
