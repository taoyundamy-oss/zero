package com.zero.v2.app.ui.settings

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.TaskMemory

internal data class TaskReviewFields(val status: String, val next: String, val acceptance: String, val pointer: String)

/** Field errors before creating a proposal; dates are values, not user-entered strings. */
internal data class MemoryReviewForm(val content: String, val type: MemoryType, val scope: String, val reason: String,
    val occurred: Long?, val from: Long?, val until: Long?) {
    fun body(task: TaskReviewFields): String = if (type == MemoryType.TASK)
        TaskMemory(task.status, task.next, task.acceptance, task.pointer.ifBlank { null }, content).encode() else content

    fun errors(task: TaskReviewFields): List<String> = buildList {
        if (content.isBlank()) add("拟确认正文：不能为空")
        if (scope.isBlank() || scope.length > 100) add("范围：必填，最多100字")
        if (reason.isBlank() || reason.length > 1000) add("复核理由：必填，最多1000字")
        if (listOf(occurred, from, until).any { it != null && it < 0 }) add("日期：不能早于1970年时间戳起点")
        if (from != null && until != null && until <= from) add("失效时间：必须晚于生效时间")
        if (type == MemoryType.TASK) addAll(taskErrors(task))
        val encoded = runCatching { body(task) }.getOrNull()
        if ((encoded?.length ?: content.length) > 4000) add("拟确认正文：含任务字段后最多4000字")
    }

    private fun taskErrors(task: TaskReviewFields): List<String> = buildList {
        if (task.status !in TaskMemory.STATUSES) add("任务状态：请选择有效状态")
        if (task.next.isBlank()) add("下一步：必填，完成或撤销时说明收尾或无需后续")
        if (task.acceptance.isBlank()) add("验收条件：不能为空")
        listOf("下一步" to task.next, "验收条件" to task.acceptance, "资料指针" to task.pointer).forEach { (label, value) ->
            if (value.length > 500 || '\n' in value || '\r' in value) add("$label：最多500字且必须单行")
        }
        if (content.trimStart().startsWith("[zero-task:")) add("拟确认正文：只填写任务正文，不粘贴内部任务协议")
    }
}
