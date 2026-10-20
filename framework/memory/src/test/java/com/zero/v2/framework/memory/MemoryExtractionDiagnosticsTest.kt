package com.zero.v2.framework.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryExtractionDiagnosticsTest {
    @Test fun failureCodesNeverContainProviderOrPersonalText() {
        assertEquals("memory_type_required", MemoryExtractionDiagnostics.failure(IllegalArgumentException("memory_type_required")))
        assertEquals("memory_source_requires_review", MemoryExtractionDiagnostics.failure(MemorySourceLimitException()))
        assertEquals("transport_failure", MemoryExtractionDiagnostics.failure(java.io.IOException("private endpoint secret")))
        assertEquals("extraction_failure", MemoryExtractionDiagnostics.failure(IllegalArgumentException("personal text")))
    }
}
