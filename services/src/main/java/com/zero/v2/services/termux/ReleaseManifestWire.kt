package com.zero.v2.services.termux

import com.zero.v2.core.common.ModuleReleaseManifest
import org.json.JSONArray
import org.json.JSONObject

/** Complete declaration transport. Declaration validation does not certify runtime enforcement. */
internal object ReleaseManifestWire {
    private val healthPattern = Regex("endpoint_window_([0-9]+)s")
    private const val MIN_PROBE_SECONDS = 5
    private const val MAX_PROBE_SECONDS = 120

    fun encode(manifest: ModuleReleaseManifest): JSONObject {
        val seconds = healthPattern.matchEntire(manifest.healthContract)?.groupValues?.get(1)?.toIntOrNull()
        require(seconds != null && seconds in MIN_PROBE_SECONDS..MAX_PROBE_SECONDS) { "invalid_health_contract" }
        require(manifest.dataSchemaVersion > 0) { "invalid_data_schema_version" }
        require(manifest.transport in setOf("http", "socket")) { "invalid_transport" }
        require(manifest.sdkRange.isNotBlank() && manifest.entrypoint.isNotBlank()) { "invalid_manifest_identity" }
        return JSONObject().put("deps", JSONArray(manifest.dependencies))
            .put("sdkRange", manifest.sdkRange).put("entrypoint", manifest.entrypoint)
            .put("capabilities", JSONArray(manifest.capabilities)).put("termuxDeps", JSONArray(manifest.termuxDeps))
            .put("inputSchema", JSONObject(manifest.inputSchema)).put("outputSchema", JSONObject(manifest.outputSchema))
            .put("resourceLimits", JSONObject(manifest.resourceLimits)).put("healthContract", manifest.healthContract)
            .put("probe_timeout_s", seconds).put("dataSchemaVersion", manifest.dataSchemaVersion)
            .put("transport", manifest.transport)
    }
}
