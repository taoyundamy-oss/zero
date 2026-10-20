package com.zero.v2.app.ui.automation

import com.zero.v2.core.moduleapi.ToolDispatcher
import com.zero.v2.core.common.FunctionResult

/** 自动化 UI 到工具执行层的 typed command；FC 名称和参数映射集中在此处。 */
sealed interface AutomationCommand {
    data class Draft(val description: String) : AutomationCommand
    data class Create(
        val description: String,
        val scheduleType: String,
        val scheduleValue: String,
        val execMode: String,
        val planJson: String
    ) : AutomationCommand
    data class Update(val id: String, val scheduleType: String, val scheduleValue: String, val title: String) : AutomationCommand
    data class RunNow(val id: String) : AutomationCommand
    data class Manage(val id: String, val action: String) : AutomationCommand
}

class AutomationCommandDispatcher(private val dispatcher: ToolDispatcher) {
    suspend fun execute(command: AutomationCommand): FunctionResult = when (command) {
        is AutomationCommand.Draft -> dispatcher.execute("draft_automation", mapOf("description" to command.description))
        is AutomationCommand.Create -> dispatcher.execute("create_automation", mapOf(
            "description" to command.description,
            "schedule_type" to command.scheduleType,
            "schedule_value" to command.scheduleValue,
            "exec_mode" to command.execMode,
            "plan_json" to command.planJson
        ))
        is AutomationCommand.Update -> dispatcher.execute("update_automation", mapOf(
            "automation_id" to command.id,
            "schedule_type" to command.scheduleType,
            "schedule_value" to command.scheduleValue,
            "title" to command.title
        ))
        is AutomationCommand.RunNow -> dispatcher.execute("run_automation_now", mapOf("automation_id" to command.id))
        is AutomationCommand.Manage -> dispatcher.execute("manage_automation", mapOf(
            "automation_id" to command.id, "action" to command.action
        ))
    }
}
