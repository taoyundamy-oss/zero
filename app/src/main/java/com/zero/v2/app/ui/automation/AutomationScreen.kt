package com.zero.v2.app.ui.automation

import com.zero.v2.app.ui.date.WheelTimeField
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.services.module.ExecutionPlan
import com.zero.v2.services.module.RunProgress
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.minTouchTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动化任务列表 — Step 3.6（DESIGN: 任务列表 + 状态卡片）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    onCreateClick: () -> Unit,
    onBack: () -> Unit,
    viewModel: AutomationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val progress by viewModel.runProgress.collectAsState()
    val lastResults by viewModel.lastResults.collectAsState()
    val lastStepResults by viewModel.lastStepResults.collectAsState()
    val lastFinals by viewModel.lastFinals.collectAsState()
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailTask = state.tasks.firstOrNull { it.id == detailId }
    val editTask = state.tasks.firstOrNull { it.id == editId }
    val listState = rememberLazyListState()
    val detailStateHolder = rememberSaveableStateHolder()

    // 每次进入本屏刷新（Tab 切换保留实例，列表可能落后于 LLM 侧创建的任务）
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }

    // 动作结果提示
    state.message?.let { msg ->
        LaunchedEffect(msg, state.messageIsError) {
            if (state.messageIsError) return@LaunchedEffect
            kotlinx.coroutines.delay(5000)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { ZeroPageHeader("自动化任务", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateClick) {
                Icon(Icons.Filled.Add, contentDescription = "创建任务")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AutomationReadStatus(state, viewModel::refresh)
            AutomationActionStatus(state, viewModel::clearMessage)

            if (state.tasks.isEmpty() && !state.loading && state.readError == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无自动化任务\n点击 + 创建任务",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        AutomationCard(
                            task = task,
                            running = state.runningId == task.id,
                            onClick = { detailId = task.id }
                        )
                    }
                }
            }
        }
    }

    detailTask?.takeIf { editId == null }?.let { task ->
        detailStateHolder.SaveableStateProvider(task.id) {
            AutomationDetailDialog(
                task = state.tasks.firstOrNull { it.id == task.id } ?: task,
                feedback = state,
                onDismissFeedback = viewModel::clearMessage,
                running = state.runningId == task.id,
                liveProgress = progress?.takeIf { it.automationId == task.id },
                lastResult = lastResults[task.id],
                lastStepResults = lastStepResults[task.id],
                lastFinal = lastFinals[task.id],
                onRunNow = { viewModel.runNow(task.id) },
                onEdit = { editId = task.id },
                onManage = { action ->
                    viewModel.manage(task.id, action) {
                        if (action == "delete") {
                            detailStateHolder.removeState(task.id)
                            detailId = null
                        }
                    }
                },
                onDismiss = {
                    detailStateHolder.removeState(task.id)
                    detailId = null
                }
            )
        }
    }

    editTask?.let { task ->
        EditScheduleDialog(
            task = task,
            feedback = state,
            onDismissFeedback = viewModel::clearMessage,
            onConfirm = { type, value, newTitle ->
                viewModel.updateSchedule(task.id, type, value, newTitle) { editId = null }
            },
            onDismiss = { editId = null }
        )
    }
}

/** Shared production read projection; failure never masquerades as an empty library. */
@Composable
internal fun AutomationReadStatus(state: AutomationUiState, onRetry: () -> Unit) {
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.readError?.let { error ->
        ZeroCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(error, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onRetry, enabled = !state.loading,
                    modifier = Modifier.minTouchTarget()) { Text("重新读取") }
            }
        }
    }
}

@Composable
internal fun AutomationActionStatus(state: AutomationUiState, onDismiss: () -> Unit) {
    state.message?.let { message ->
        ZeroCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            containerColor = if (state.messageIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (state.messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss, modifier = Modifier.minTouchTarget()) {
                    Text("关闭提示", color = if (state.messageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** 任务卡片 */
@Composable
private fun AutomationCard(task: AutomationEntity, running: Boolean, onClick: () -> Unit) {
    ZeroCard(modifier = Modifier.fillMaxWidth().minTouchTarget().clickable { onClick() }) {
        Column(Modifier.padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                StatusChip(task.status)
            }
            Spacer(Modifier.height(4.dp))
            Text("${scheduleLabel(task)} · 已运行 ${task.runCount} 次" +
                    if (task.nextRunAt != Long.MAX_VALUE)
                        " · 下次 ${formatTime(task.nextRunAt)}" else if (task.scheduleType == "MANUAL") " · 手动执行" else " · 暂未安排下一次执行",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (running) {
                Spacer(Modifier.height(4.dp))
                Text("执行中…", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status) {
        "ACTIVE" -> "已启用" to MaterialTheme.colorScheme.primaryContainer
        "PAUSED" -> "已暂停" to MaterialTheme.colorScheme.surfaceContainerHigh
        else -> status to MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(shape = MaterialTheme.shapes.small, color = color,
        contentColor = if (status == "ACTIVE") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** 详情对话框（D42）: 上部执行过程（可折叠默认收）+ 下部最终结果（全文常驻）+ 操作 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutomationDetailDialog(
    task: AutomationEntity,
    feedback: AutomationUiState,
    onDismissFeedback: () -> Unit,
    running: Boolean,
    liveProgress: RunProgress?,
    lastResult: String?,
    lastStepResults: Map<Int, String>?,
    lastFinal: String?,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onManage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val plan = remember(task.planJson) { ExecutionPlan.fromJson(task.planJson) }
    // 执行中的实时步骤结果，否则用上次完整结果
    val stepResults = if (liveProgress != null && !liveProgress.done) liveProgress.stepResults
                      else (lastStepResults ?: emptyMap())
    val stepDone = { idx: Int -> stepResults.containsKey(idx) }
    val isReact = task.execMode != "PLAN"
    val modeLabel = when (task.execMode) {
        "SELF_DRIVE" -> "自驱回合"
        "REACT" -> "智能执行"
        "PLAN" -> "静态步骤"
        else -> task.execMode
    }
    var processExpanded by rememberSaveable(task.id) { mutableStateOf(false) }
    val finalContent = liveProgress?.finalContent ?: lastFinal

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务详情") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectionContainer { Text(task.title, style = MaterialTheme.typography.titleMedium) }
                Text("${scheduleLabel(task)} · $modeLabel · 已运行 ${task.runCount} 次",
                    style = MaterialTheme.typography.labelMedium)

                AutomationActionStatus(feedback, onDismissFeedback)
                // 实时进度（执行中）
                if (liveProgress != null && !liveProgress.done) {
                    LinearProgressIndicator(
                        progress = { if (liveProgress.totalSteps > 0)
                            liveProgress.stepIndex.toFloat() / liveProgress.totalSteps else 0f },
                        modifier = Modifier.fillMaxWidth())
                    Text("${if (isReact) "第 ${liveProgress.stepIndex}/${liveProgress.totalSteps} 轮" else "步骤 ${liveProgress.stepIndex}/${liveProgress.totalSteps}"}: ${liveProgress.lastResult}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }

                // ═══ 上部: 执行过程（默认折叠，展开见完整结果）═══
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().minTouchTarget().clickable { processExpanded = !processExpanded }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("执行过程（${stepResults.size}${if (isReact) " 轮" else " 步"}）",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f))
                    Text(if (processExpanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (processExpanded) {
                    if (!isReact && plan != null) {
                        plan.steps.forEach { step ->
                            CollapsibleStepRow(
                                prefix = "${step.index}. ${step.description}",
                                tag = if (step.tool != null) step.tool!! else "推理",
                                done = stepDone(step.index),
                                result = stepResults[step.index]
                            )
                        }
                    }
                    stepResults.toSortedMap().forEach { (_, result) ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer { Text(result, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(8.dp)) }
                        }
                    }
                }

                // ═══ 下部: 最终结果（全文，始终可见）═══
                HorizontalDivider()
                if (finalContent != null) {
                    Text("最终结果", style = MaterialTheme.typography.titleSmall)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer { Text(finalContent, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp)) }
                    }
                } else {
                    lastResult?.let {
                        Text("上次执行", style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRunNow, enabled = !running, modifier = Modifier.minTouchTarget()) {
                if (running) Text("执行中…") else Text("立即执行")
            }
        },
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                if (task.status == "ACTIVE") {
                    IconButton(onClick = { onManage("pause") }) {
                        Icon(Icons.Filled.Pause, contentDescription = "暂停")
                    }
                } else {
                    IconButton(onClick = { onManage("resume") }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "恢复")
                    }
                }
                IconButton(onClick = { onManage("delete") }) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

/** 可折叠步骤行: 完成后点击展开完整结果 */
@Composable
private fun CollapsibleStepRow(prefix: String, tag: String, done: Boolean, result: String?) {
    var expanded by rememberSaveable(prefix) { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().let { m ->
                if (result != null) m.minTouchTarget().clickable { expanded = !expanded } else m
            }
        ) {
            Text(if (done) "已有结果" else "待执行", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(4.dp))
            Text("$prefix  [$tag]",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f))
            if (result != null) {
                Text(if (expanded) "收起" else "结果", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (expanded && result != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp)
            ) {
                SelectionContainer { Text(result, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

/** 编辑调度对话框（类型/时间/标题） */
@Composable
private fun EditScheduleDialog(
    task: AutomationEntity,
    feedback: AutomationUiState,
    onDismissFeedback: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var scheduleType by rememberSaveable(task.id) { mutableStateOf(task.scheduleType) }
    // INTERVAL 显示为分钟
    var scheduleValue by rememberSaveable(task.id) {
        mutableStateOf(
            if (task.scheduleType == "INTERVAL")
                ((task.scheduleValue.toLongOrNull() ?: 0) / 60).toString()
            else task.scheduleValue
        )
    }
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑任务") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AutomationActionStatus(feedback, onDismissFeedback)
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("MANUAL" to "手动", "DAILY" to "每日", "INTERVAL" to "间隔").forEach { (type, label) ->
                        FilterChip(
                            selected = scheduleType == type,
                            onClick = { scheduleType = type },
                            label = { Text(label) }, modifier = Modifier.fillMaxWidth().minTouchTarget())
                    }
                }
                when (scheduleType) {
                    "DAILY" -> WheelTimeField("执行时间", scheduleValue) { scheduleValue = it }
                    "INTERVAL" -> OutlinedTextField(
                        value = scheduleValue, onValueChange = { scheduleValue = it },
                        label = { Text("间隔（分钟）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(scheduleType, scheduleValue, title) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun scheduleLabel(task: AutomationEntity): String = when (task.scheduleType) {
    "DAILY" -> "每日 ${task.scheduleValue}"
    "INTERVAL" -> "每 ${task.scheduleValue.toIntOrNull()?.let { sec ->
        if (sec % 60 == 0) "${sec / 60} 分钟" else "$sec 秒"
    } ?: task.scheduleValue}"
    else -> "手动"
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
