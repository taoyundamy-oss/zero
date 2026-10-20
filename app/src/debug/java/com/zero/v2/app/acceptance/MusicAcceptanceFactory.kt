package com.zero.v2.app.acceptance

import android.content.Context
import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.app.music.MusicModule
import com.zero.v2.app.music.MusicModuleConfig
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.services.agent.ExactAlarmScheduler
import com.zero.v2.services.agent.TriggerHub
import javax.inject.Inject

/** Debug only: production engine with isolated Room; no periodic startup or personal schedule scan. */
class MusicAcceptanceFactory @Inject constructor(
    private val systemBus: SystemBus,
    private val alarms: ExactAlarmScheduler,
    private val runtime: AgentRuntime,
    private val triggers: TriggerHub,
) {
    fun create(context: Context, db: ZeroDatabase): MusicModule {
        val traces = AgentTraceManager(db.agentTraceEventDao())
        return MusicModule(context, WorkDirRepository(context, traces), db.musicDao(), systemBus,
            alarms, runtime, triggers, traces).also {
            (it.config as MusicModuleConfig).autoScanOnStart = false
        }
    }
}
