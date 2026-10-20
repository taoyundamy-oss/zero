package com.zero.v2.services.llm

import android.content.Context
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Single configuration owner. Legacy JSON is retained untouched for recovery. */
@Singleton
class LlmProfileStore @Inject constructor(
    @ApplicationContext context: Context,
    private val traceManager: AgentTraceManager,
) {
    private val prefs = context.getSharedPreferences("zero_llm_profiles", Context.MODE_PRIVATE)
    private val persistence = ProfilePersistence(
        prefs.getString("snapshot_json", null), prefs.getString("profiles_json", null),
    ) { raw -> prefs.edit().putString("snapshot_json", raw).commit() }
    private val _profiles = MutableStateFlow(persistence.profiles)
    val profiles: StateFlow<List<LlmProfile>> = _profiles.asStateFlow()
    val storageError: String? get() = persistence.error

    @Synchronized
    fun manage(edit: ProfileEdit) { publish(ProfileManagement.apply(_profiles.value, edit)) }

    @Synchronized
    fun upsert(profile: LlmProfile) {
        if (profile.kind == LlmProfile.Kind.CHAT) ModelContracts.get(profile.contractId).validate(profile)
        val current = _profiles.value.toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) current[index] = profile else current.add(profile)
        publish(current)
    }

    /** A shared connection is updated as one snapshot, never as sequential model writes. */
    @Synchronized
    fun updateConnection(connection: ConnectionProfile) {
        require(_profiles.value.any { it.connectionId == connection.id }) { "连接不存在" }
        publish(_profiles.value.map { if (it.connectionId == connection.id) connection.project(it) else it })
    }

    @Synchronized
    fun setPrimary(id: String, kind: LlmProfile.Kind) {
        require(_profiles.value.any { it.id == id && it.kind == kind })
        publish(_profiles.value.map { if (it.kind == kind) it.copy(enabled = it.id == id) else it })
    }

    private fun publish(next: List<LlmProfile>) {
        require(next.all { it.tokenPrices.validationError() == null }) { "模型价格格式无效" }
        persistence.save(next)
        _profiles.value = persistence.profiles
        traceManager.record("llm_profiles", NodeType.SYSTEM_EVENT, "openai_chat",
            "snapshot_saved: ${next.size} profiles")
    }

    fun resetToDefault(id: String) {
        LlmProfile.defaults().find { it.id == id }?.let(::upsert)
    }

    fun get(id: String): LlmProfile? = _profiles.value.find { it.id == id }
    fun byKind(kind: LlmProfile.Kind): List<LlmProfile> = _profiles.value.filter { it.kind == kind }
    fun getEnabled(kind: LlmProfile.Kind): List<LlmProfile> =
        _profiles.value.filter { it.kind == kind && it.enabled }
}
