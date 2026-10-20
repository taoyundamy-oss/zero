package com.zero.v2.framework.memory

/** Never persist exception text from a provider; only these client-owned reason codes. */
internal object MemoryExtractionDiagnostics {
    private val reasons = setOf("fact_result_limit", "memory_source_unknown", "memory_source_unit_invalid",
        "memory_source_unit_required", "memory_type_required", "memory_item_invalid", "memory_source_requires_review")
    fun failure(error: Exception): String = error.message?.takeIf { it in reasons } ?: when (error) {
        is java.io.IOException -> "transport_failure"
        is org.json.JSONException -> "invalid_json"
        else -> "extraction_failure"
    }
}
