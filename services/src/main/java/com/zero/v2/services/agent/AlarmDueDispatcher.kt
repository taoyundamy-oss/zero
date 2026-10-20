package com.zero.v2.services.agent

import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ToolExecutionGuard
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.module.AutomationModule
import com.zero.v2.services.module.SystemEventModule
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Action dispatch only; broadcast lifetime and durable handoff belong to the caller. */
@Singleton
class AlarmDueDispatcher @Inject constructor(
    private val reminders: Provider<SystemEventModule>,
    private val automations: Provider<AutomationModule>,
    private val systemBus: SystemBus,
    private val trace: AgentTraceManager,
    private val guard: Provider<ToolExecutionGuard>,
) {
    companion object {
        private const val TRACE_REASON_LIMIT = 60
        const val ACTION_BOOT_RESTORE = "com.zero.v2.action.BOOT_RESTORE"
        val WORK_ACTIONS = ExactAlarmReceiver.DUE_ACTIONS + ACTION_BOOT_RESTORE
    }

    suspend fun dispatch(action: String?): FunctionResult =
        when (action) {
            ACTION_BOOT_RESTORE -> {
                val module = reminders.get()
                runModule(module) { module.restoreAfterBoot() }
            }
            ExactAlarmScheduler.ACTION_REMINDER_DUE -> {
                trace.record("exact_alarm", NodeType.SYSTEM_EVENT, "alarm", "alarm_fired: REMINDER_DUE")
                val module = reminders.get()
                runModule(module) { module.execute() }
            }
            ExactAlarmScheduler.ACTION_AUTOMATION_DUE -> {
                trace.record("exact_alarm", NodeType.SYSTEM_EVENT, "alarm", "alarm_fired: AUTOMATION_DUE")
                val module = automations.get()
                runModule(module) { module.execute() }
            }
            ExactAlarmScheduler.ACTION_MUSIC_DUE -> {
                trace.record("exact_alarm", NodeType.SYSTEM_EVENT, "alarm", "alarm_fired: MUSIC_DUE")
                systemBus.onMusicDue.emit(Unit)
                FunctionResult.success("music_signal_emitted")
            }
            else -> FunctionResult.failure("unknown_alarm_action")
        }

    private suspend fun runModule(module: IZeroModule, block: suspend () -> Unit): FunctionResult {
        val result = guard.get().executeForModule(module.metadata.id, "alarm") {
            block()
            FunctionResult.success("due_scan_completed")
        }
        if (!result.success) {
            val reason = result.errorMessage?.take(TRACE_REASON_LIMIT)
            trace.record("exact_alarm", NodeType.SYSTEM_EVENT, module.metadata.id,
                "alarm_job_not_run: module=${module.metadata.id} reason=$reason")
        }
        return result
    }
}
