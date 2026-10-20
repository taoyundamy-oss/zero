package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.TaskMemory
import com.zero.v2.core.data.dao.MemoryReviewDao
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryReviewTimes
import com.zero.v2.app.ui.date.WheelDateTimeField

/** Edits are proposals, never in-place writes. Task encoding is owned by this adapter. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryReviewEditDialog(m: MemoryEntity, busy: Boolean, notice: String?, dismiss: () -> Unit,
    save: (String, MemoryType, String, String, MemoryReviewTimes) -> Unit) {
    val task = remember(m.id) { TaskMemory.decode(m.content) }
    var content by rememberSaveable(m.id) { mutableStateOf(task?.text ?: m.content) }
    var type by rememberSaveable(m.id) { mutableStateOf(m.type.takeIf { it in MemoryReviewDao.REVIEW_TYPES } ?: MemoryType.FACT) }
    var scope by rememberSaveable(m.id) { mutableStateOf(m.scope) }
    var reason by rememberSaveable(m.id) { mutableStateOf("") }
    var status by rememberSaveable(m.id) { mutableStateOf(task?.status ?: "ACTIVE") }
    var next by rememberSaveable(m.id) { mutableStateOf(task?.next.orEmpty()) }
    var acceptance by rememberSaveable(m.id) { mutableStateOf(task?.acceptance.orEmpty()) }
    var pointer by rememberSaveable(m.id) { mutableStateOf(task?.pointer.orEmpty()) }
    var occurred by rememberSaveable(m.id) { mutableStateOf(m.occurredAt) }
    var from by rememberSaveable(m.id) { mutableStateOf(m.validFrom) }
    var until by rememberSaveable(m.id) { mutableStateOf(m.validUntil) }
    val fields = MemoryReviewForm(content, type, scope, reason, occurred, from, until)
    val taskFields = TaskReviewFields(status, next, acceptance, pointer)
    val errors = fields.errors(taskFields)
    val proposal = if (errors.isEmpty()) fields.body(taskFields) to MemoryReviewTimes(occurred, from, until) else null
    AlertDialog(onDismissRequest = dismiss, title = { Text("修订旧记忆（确认前不生效）") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("原记录：${reviewBody(m.content)}\n记录时间：${java.util.Date(m.createdAt)}")
            OutlinedTextField(content, { content = it }, label = { Text("拟确认正文") })
            FlowRow { MemoryReviewDao.REVIEW_TYPES.forEach { t ->
                FilterChip(type == t, { type = t }, label = { Text(reviewTypeLabel(t)) })
            } }
            OutlinedTextField(scope, { scope = it }, label = { Text("范围") })
            if (type == MemoryType.TASK) {
                FlowRow { TaskMemory.STATUSES.forEach { t -> FilterChip(status == t, { status = t }, label = { Text(t) }) } }
                OutlinedTextField(next, { next = it }, label = { Text("下一步（终态写无需后续或收尾）") })
                OutlinedTextField(acceptance, { acceptance = it }, label = { Text("验收条件") })
                OutlinedTextField(pointer, { pointer = it }, label = { Text("资料指针（可空）") })
            }
            Text("点击后滚动选择日期和时间，按本机时区保存；可清空为未知，不用确认日替代发生日。")
            WheelDateTimeField("发生时间", occurred, !busy) { occurred = it }
            WheelDateTimeField("生效时间", from, !busy) { from = it }
            WheelDateTimeField("失效时间", until, !busy) { until = it }
            OutlinedTextField(reason, { reason = it }, label = { Text("复核理由") })
            Text("保存仅创建提案；下一页核对正文、类型、范围和时间后确认。未知来源不会补成历史原话。")
            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it) }
        }
    }, confirmButton = { TextButton(onClick = { proposal?.let { save(it.first, type, scope, reason, it.second) } },
        enabled = !busy && proposal != null) { Text("保存提案") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

