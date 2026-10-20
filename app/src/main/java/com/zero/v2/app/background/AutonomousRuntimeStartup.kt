package com.zero.v2.app.background

import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.agent.AgentRuntime

/** Application startup seam; independent of optional screen and gesture permissions. */
internal suspend fun initializeAutonomousRuntime(lifecycle: ModuleLifecycleManager, runtime: AgentRuntime) {
    lifecycle.initializeAll()
    if (runtime.isEnabled) {
        checkNotNull(lifecycle.safeCall(runtime, "application_runtime_start") { runtime.startLoop() })
    }
}
