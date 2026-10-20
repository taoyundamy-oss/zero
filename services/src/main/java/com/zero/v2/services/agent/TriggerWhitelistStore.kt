package com.zero.v2.services.agent

import android.content.Context
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 触发白名单存储 — A2a（D70/D71）。
 *
 * 白名单内 App 的通知才触发自主思考（D70）。空名单=不触发+引导配置。
 * SharedPreferences StringSet + StateFlow（Live 白名单同模式）。
 */
@Singleton
class TriggerWhitelistStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traceManager: AgentTraceManager
) {
    companion object {
        private const val PREFS = "zero_trigger_whitelist"
        private const val KEY_PACKAGES = "whitelist_packages"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _packages = MutableStateFlow<Set<String>>(emptySet())
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    init { load() }

    private fun load() {
        try {
            _packages.value = prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
        } catch (_: Exception) { _packages.value = emptySet() }
    }

    private fun persist() {
        prefs.edit().putStringSet(KEY_PACKAGES, _packages.value).apply()
    }

    fun contains(packageName: String): Boolean = _packages.value.contains(packageName)

    fun isEmpty(): Boolean = _packages.value.isEmpty()

    fun add(packageName: String) {
        _packages.value = _packages.value + packageName
        persist()
        traceManager.record("trigger_whitelist", NodeType.SYSTEM_EVENT, "trigger_hub",
            "package_added: $packageName")
    }

    fun remove(packageName: String) {
        _packages.value = _packages.value - packageName
        persist()
        traceManager.record("trigger_whitelist", NodeType.SYSTEM_EVENT, "trigger_hub",
            "package_removed: $packageName")
    }

    fun setAll(packages: Set<String>) {
        _packages.value = packages
        persist()
        traceManager.record("trigger_whitelist", NodeType.SYSTEM_EVENT, "trigger_hub",
            "whitelist_set: ${packages.size} packages")
    }
}
