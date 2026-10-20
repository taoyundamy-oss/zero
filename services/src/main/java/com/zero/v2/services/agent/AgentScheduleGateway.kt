package com.zero.v2.services.agent

import javax.inject.Inject

/** Shared entry to scheduled autonomous work: preserves alarm ownership and budget enforcement. */
class AgentScheduleGateway @Inject constructor(
    private val runtime: AgentRuntime,
    private val alarms: ExactAlarmScheduler,
    private val triggers: TriggerHub,
    private val budget: BudgetGuard,
    private val configStore: com.zero.v2.core.data.store.ModuleConfigStore,
) {
    fun kick() = runtime.kick()
    fun scheduleReminder(at: Long) = alarms.scheduleReminder(at)
    fun scheduleAutomation(at: Long) = alarms.scheduleAutomation(at)
    fun fireSelfDrive(payload: String, reason: String) = triggers.fireSelfDrive(payload, reason)
    suspend fun preCheck(caller: String) = budget.preCheck(caller)
    suspend fun isAutonomousOff(): Boolean = configStore.get("agent_behavior")?.let {
        org.json.JSONObject(it).optString("mode", "SMART") == "OFF"
    } == true
}
