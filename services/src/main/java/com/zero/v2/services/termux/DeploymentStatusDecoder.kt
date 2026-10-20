package com.zero.v2.services.termux

import com.zero.v2.core.common.ReleaseState
import com.zero.v2.core.moduleapi.DynamicRuntimeStatus
import com.zero.v2.core.moduleapi.ModuleDeploymentStatus
import com.zero.v2.core.moduleapi.ReleaseVersionStatus
import com.zero.v2.core.moduleapi.SystemStateAssembler.TermuxInputs
import org.json.JSONObject

/** Allowlisted metadata from the supervisor authority; no code, manifests, endpoints or raw errors. */
object DeploymentStatusDecoder {
    private const val RECENT_HISTORY_LIMIT = 3
    private val identifier = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}")
    private val hash = Regex("[a-fA-F0-9]{64}")
    private val version = Regex("[0-9]+(?:\\.[0-9]+){0,3}")
    private val runtimeStates = setOf("ACTIVE", "STARTING", "STOPPED", "FAILED", "DISABLED", "CIRCUIT_OPEN", "CRASHED")
    fun validId(value: String) = identifier.matches(value)

    fun health(raw: JSONObject, port: Int, observedAt: Long): TermuxInputs {
        val rows = raw.optJSONArray("modules").takeIf { SupervisorBundle.isReady(raw) }
        val modules = rows?.let { values ->
            (0 until values.length()).mapNotNull { index ->
                val item = values.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf(::validId) ?: return@mapNotNull null
                DynamicRuntimeStatus(id, item.optString("state").takeIf { it in runtimeStates } ?: "UNKNOWN")
            }.distinctBy { it.id }.sortedBy { it.id }
        }
        return TermuxInputs(raw.optBoolean("alive"), port, raw.optInt("pid").takeIf { it > 0 }, observedAt,
            raw.opt("rootfs") as? Boolean, raw.optString("sdk_version").takeIf(version::matches),
            (raw.opt("release_protocol") as? Number)?.toInt(), modules)
    }

    fun deployment(moduleId: String, reply: JSONObject?, observedAt: Long): ModuleDeploymentStatus {
        if (reply == null || !reply.optBoolean("ok")) return unknown(moduleId, "supervisor_unavailable")
        return try {
            require(validId(moduleId) && reply.has("active") && reply.optJSONArray("history") != null)
            val active = if (reply.isNull("active")) null else release(reply.getJSONObject("active"), moduleId)
            val history = reply.getJSONArray("history")
            val recent = (0 until history.length()).map { release(history.getJSONObject(it), moduleId) }
                .filter { it.deploymentId != active?.deploymentId }
                .sortedByDescending { it.updatedAt }.take(RECENT_HISTORY_LIMIT)
            ModuleDeploymentStatus(moduleId, active, recent, observedAt, reply.opt("emergency") as? Boolean)
        } catch (_: Exception) { unknown(moduleId, "invalid_status_reply") }
    }

    private fun release(row: JSONObject, moduleId: String): ReleaseVersionStatus {
        require(row.getString("module_id") == moduleId)
        val id = row.getString("deployment_id").also { require(validId(it)) }
        val contentHash = row.getString("content_hash").also { require(hash.matches(it)) }
        val state = row.getString("state").also { value ->
            require(ReleaseState.entries.any { it.name == value })
        }
        val updatedAt = if (row.has("updated_at")) row.getLong("updated_at") else row.getLong("created_at")
        require(updatedAt > 0L)
        return ReleaseVersionStatus(id, contentHash, state, updatedAt)
    }

    fun unknown(moduleId: String, code: String) =
        ModuleDeploymentStatus(moduleId, null, emptyList(), 0L, errorCode = code)
}
