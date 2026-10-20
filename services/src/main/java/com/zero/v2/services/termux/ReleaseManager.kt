package com.zero.v2.services.termux

import com.zero.v2.core.common.ModuleReleaseManifest
import com.zero.v2.core.common.ReleaseState
import com.zero.v2.core.data.dao.DeploymentJournalDao
import com.zero.v2.core.data.entity.DeploymentJournalEntity
import com.zero.v2.core.data.store.ModuleConfigStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Supervisor owns the durable journal and active pointer; Room is a replaceable UI mirror. */
@Singleton
class ReleaseManager @Inject constructor(
    val termuxBridge: TermuxBridge,
    private val journalDao: DeploymentJournalDao,
    val configStore: ModuleConfigStore,
) {
    val operationsMutex = Mutex()
    private val mirrorMutex = Mutex()

    data class ReleaseResult(
        val ok: Boolean,
        val deploymentId: String? = null,
        val state: ReleaseState? = null,
        val error: String? = null,
    )

    suspend fun publish(moduleId: String, code: String, manifest: ModuleReleaseManifest): ReleaseResult {
        val prepared = prepareRelease(moduleId, code, manifest)
        val id = prepared.deploymentId
        return if (prepared.ok && id != null) {
            val validated = validateRelease(id, moduleId)
            if (validated.ok) activateRelease(id, moduleId) else validated
        } else prepared
    }

    suspend fun prepareRelease(moduleId: String, code: String, manifest: ModuleReleaseManifest): ReleaseResult {
        val declaration = try { ReleaseManifestWire.encode(manifest) }
        catch (_: Exception) { return ReleaseResult(false, error = "发布声明格式无效，请检查契约字段") }
        return if (termuxBridge.isEmergencyStop || !termuxBridge.ensureSupervisor()) {
            ReleaseResult(false, error = "紧急停止已开启或 supervisor 不可用")
        } else {
            val config = configStore.get(moduleId)?.let { JSONObject(it) } ?: JSONObject()
            val body = JSONObject().put("module_id", moduleId).put("code", code)
                .put("config", config).put("transport", manifest.transport)
                .put("manifest", declaration)
            operation("prepare", body, moduleId)
        }
    }

    suspend fun validateRelease(deploymentId: String, moduleId: String): ReleaseResult =
        operation("validate", JSONObject().put("deployment_id", deploymentId), moduleId, VALIDATION_TIMEOUT_MS)

    suspend fun activateRelease(deploymentId: String, moduleId: String): ReleaseResult =
        operation("activate", JSONObject().put("deployment_id", deploymentId), moduleId)

    suspend fun rollbackRelease(moduleId: String): ReleaseResult =
        operation("rollback", JSONObject().put("module_id", moduleId), moduleId)

    /** Called before dispatch and after the actual outcome; unknown outcomes never count as success. */
    suspend fun observe(moduleId: String, success: Boolean? = null): ReleaseResult =
        operation("observe", JSONObject().put("module_id", moduleId).apply {
            if (success != null) put("success", success)
        }, moduleId)

    private suspend fun operation(
        action: String, body: JSONObject, moduleId: String, timeoutMs: Long = OPERATION_TIMEOUT_MS,
    ): ReleaseResult {
        if (termuxBridge.isEmergencyStop && action != "rollback") {
            return ReleaseResult(false, error = "紧急停止已开启")
        }
        val reply = termuxBridge.supPost("/release/$action", body, timeoutMs)
        reconcile(moduleId)
        return ReleaseReply.decode(reply)
    }

    suspend fun reconcile(moduleId: String): Boolean = mirrorMutex.withLock {
        val reply = termuxBridge.supGet("/release/status?module_id=$moduleId")
            ?: return@withLock false
        if (!reply.optBoolean("ok")) return@withLock false
        journalDao.replaceMirror(moduleId, ReleaseReply.history(reply))
        true
    }

    /** Read the authority without ensureSupervisor, module actions or Room mirror writes. */
    suspend fun readStatus(moduleId: String): com.zero.v2.core.moduleapi.ModuleDeploymentStatus {
        require(DeploymentStatusDecoder.validId(moduleId))
        val reply = termuxBridge.supGet("/release/status?module_id=$moduleId", STATUS_TIMEOUT_MS)
        return DeploymentStatusDecoder.deployment(moduleId, reply, System.currentTimeMillis())
    }

    companion object {
        private const val VALIDATION_TIMEOUT_MS = 600_000L
        private const val OPERATION_TIMEOUT_MS = 60_000L
        private const val STATUS_TIMEOUT_MS = 1500L
    }
}

/** Production wire decoding, also exercised by contract tests. */
internal object ReleaseReply {
    fun decode(reply: JSONObject?): ReleaseManager.ReleaseResult {
        if (reply == null) return ReleaseManager.ReleaseResult(false, error = "supervisor 无响应；查询部署状态后重试")
        val state = ReleaseState.entries.firstOrNull { it.name == reply.optString("state") }
        return ReleaseManager.ReleaseResult(
            ok = reply.optBoolean("ok"),
            deploymentId = reply.optString("deployment_id").takeUnless { it.isBlank() || it == "null" },
            state = state,
            error = reply.optString("error").takeUnless { it.isBlank() || it == "null" },
        )
    }

    fun history(reply: JSONObject): List<DeploymentJournalEntity> {
        val rows = reply.optJSONArray("history") ?: return emptyList()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            DeploymentJournalEntity(
                deploymentId = row.getString("deployment_id"), moduleId = row.getString("module_id"),
                contentHash = row.getString("content_hash"), manifest = row.getJSONObject("manifest").toString(),
                state = row.getString("state"), codeSize = row.optInt("code_size"), attempts = row.optInt("attempts"),
                createdAt = row.getLong("created_at"), updatedAt = row.optLong("updated_at", row.getLong("created_at")),
                lastError = row.optString("error").takeUnless { it == "null" }.orEmpty(),
            )
        }
    }
}
