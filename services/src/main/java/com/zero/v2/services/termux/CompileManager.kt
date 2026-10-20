package com.zero.v2.services.termux

import com.zero.v2.core.common.ModuleReleaseManifest
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Parses candidate metadata only. Runtime validation and publication belong to ReleaseManager. */
@Singleton
class CompileManager @Inject constructor() {
    data class ModuleManifest(
        val deps: List<String> = emptyList(),
        val termuxDeps: List<String> = emptyList(),
        val runtime: String = "python",
        val configSchema: String = "[]",
        val release: ModuleReleaseManifest = ModuleReleaseManifest(),
        val healthDeclared: Boolean = false,
    ) {
        fun toReleaseManifest(probeTimeoutSeconds: Int): ModuleReleaseManifest = release.copy(
            dependencies = deps, termuxDeps = termuxDeps,
            healthContract = if (healthDeclared) release.healthContract else "endpoint_window_${probeTimeoutSeconds}s",
        )
    }

    fun parseManifest(code: String): ModuleManifest? {
        val line = code.lineSequence().firstOrNull { it.trimStart().startsWith("# ZERO_MANIFEST:") }
            ?: return ModuleManifest()
        return try {
            val obj = JSONObject(line.substringAfter("# ZERO_MANIFEST:").trim())
            require(text(obj, "runtime", "python") == "python")
            val transport = text(obj, "transport", "http")
            val release = ModuleReleaseManifest(
                sdkRange = text(obj, "sdkRange", ">=1.0"),
                entrypoint = text(obj, "entrypoint", if (transport == "socket") "serve_socket" else "serve"),
                capabilities = strings(obj, "capabilities"), dependencies = strings(obj, "deps"),
                termuxDeps = strings(obj, "termuxDeps"), inputSchema = objectText(obj, "inputSchema"),
                outputSchema = objectText(obj, "outputSchema"), resourceLimits = objectText(obj, "resourceLimits"),
                healthContract = text(obj, "healthContract", "endpoint_window_15s"),
                dataSchemaVersion = if (obj.has("dataSchemaVersion")) {
                    val value = obj.get("dataSchemaVersion")
                    require(value is Int) { "invalid_data_schema_version" }; value
                } else 1,
                transport = transport,
            )
            ReleaseManifestWire.encode(release)
            ModuleManifest(
                deps = release.dependencies, termuxDeps = release.termuxDeps,
                configSchema = if (obj.has("configSchema")) obj.getJSONArray("configSchema").toString() else "[]",
                release = release, healthDeclared = obj.has("healthContract"),
            )
        } catch (_: org.json.JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun strings(obj: JSONObject, key: String): List<String> = if (!obj.has(key)) emptyList() else
        obj.getJSONArray(key).let { arr -> (0 until arr.length()).map {
            val value = arr.get(it); require(value is String && value.isNotBlank()); value
        } }

    private fun text(obj: JSONObject, key: String, default: String): String =
        if (!obj.has(key)) default else obj.get(key).let { require(it is String); it }

    private fun objectText(obj: JSONObject, key: String): String = if (!obj.has(key)) "{}" else
        obj.get(key).let { value ->
            when (value) {
                is JSONObject -> value.toString()
                is String -> JSONObject(value).toString()
                else -> throw IllegalArgumentException("invalid_schema_object")
            }
        }
}
