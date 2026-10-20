package com.zero.v2.app.background

import android.content.Context
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.services.agent.AgentRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider

/** Boot starts the existing foreground lifetime; the service awaits shared module readiness. */
class AutonomousBootHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ModuleConfigStore,
    private val runtime: Provider<AgentRuntime>,
) {
    suspend fun restore() = restoreAutonomousHost(configStore, { runtime.get().isEnabled }) {
        AutonomousKeepAliveService.start(context)
    }
}

internal suspend fun restoreAutonomousHost(
    configStore: ModuleConfigStore,
    enabled: () -> Boolean,
    start: () -> Unit,
): Boolean {
    val mode = configStore.get("agent_behavior")?.let { JSONObject(it).optString("mode", "SMART") } ?: "SMART"
    if (mode == "OFF" || !enabled()) return false
    start()
    return true
}
