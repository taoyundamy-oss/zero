package com.zero.v2.app.acceptance

import android.content.Context
import com.zero.v2.core.bus.ChatBus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.moduleapi.ToolRuntime
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.*
import com.zero.v2.services.llm.ModelSelector
import com.zero.v2.services.module.AutomationModule
import javax.inject.Inject

/** Test-only assembly: callers provide isolated data, budget preferences and a rejecting alarm context. */
class AutomationAcceptanceFactory @Inject constructor(private val triggers: TriggerHub) {
    fun createSystem(context: Context, db: ZeroDatabase, store: ModuleConfigStore, runtime: AgentRuntime,
        chat: ChatBus): com.zero.v2.services.module.SystemEventModule {
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val gateway = AgentScheduleGateway(runtime, ExactAlarmScheduler(context), triggers, BudgetGuard(context, store, trace), store)
        return com.zero.v2.services.module.SystemEventModule(gateway, chat, com.zero.v2.core.bus.SystemBusImpl(),
            trace, db.systemStateDao(), context)
    }

    fun create(context: Context, db: ZeroDatabase, store: ModuleConfigStore, runtime: AgentRuntime,
        chat: ChatBus, tools: ToolRuntime): AutomationModule {
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val gateway = AgentScheduleGateway(runtime, ExactAlarmScheduler(context), triggers, BudgetGuard(context, store, trace), store)
        return AutomationModule(tools, gateway, chat, ModelSelector(), db.automationDao(), trace)
    }
}
