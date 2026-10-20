package com.zero.v2.app.ui.automation

import com.zero.v2.app.ui.date.WheelTimeField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.minTouchTarget

/**
 * 创建自动化任务 — Step 3.6 草稿确认流。
 *
 * 阶段 1: 自然语言需求 → "✨ AI 生成任务卡片"
 * 阶段 2: LLM 生成结构化草稿（标题/调度/计划步骤）→ 用户编辑 → 确认创建（所见即所得）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAutomationScreen(
    onBack: () -> Unit,
    onCreateDone: () -> Unit,
    viewModel: AutomationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val drafting by viewModel.drafting.collectAsState()
    var description by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            ZeroPageHeader(if (draft == null) "创建自动化任务" else "确认任务卡片", {
                if (!state.creating && !drafting) {
                    if (draft != null) viewModel.discardDraft() else onBack()
                }
            })
        }
    ) { padding ->
        if (draft == null) {
            // ── 阶段 1: 需求描述 ──
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AutomationActionStatus(state, viewModel::clearMessage)
                Text("任务需求", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 5,
                    placeholder = { Text("如：每天早上9点搜索最新的AI新闻，整理后推送通知给我") },
                    supportingText = { Text("AI 将生成结构化任务卡片（计划步骤 + 调度建议），可编辑后确认") },
                    enabled = !drafting
                )
                Button(
                    onClick = { viewModel.generateDraft(description.trim()) },
                    enabled = description.isNotBlank() && !drafting,
                    modifier = Modifier.fillMaxWidth().minTouchTarget()
                ) {
                    if (drafting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("AI 正在生成任务卡片…")
                    } else {
                        Text("生成任务卡片")
                    }
                }
            }
        } else {
            // ── 阶段 2: 编辑并确认 ──
            val d = draft!!
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AutomationActionStatus(state, viewModel::clearMessage)
                Text("任务卡片", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = d.title,
                    onValueChange = { viewModel.updateDraft(title = it) },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !state.creating
                )

                Text("执行方式", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SELF_DRIVE" to "自驱回合", "REACT" to "智能执行", "PLAN" to "静态步骤").forEach { (mode, _) ->
                        FilterChip(
                            selected = d.execMode == mode,
                            onClick = { viewModel.updateDraft(execMode = mode) },
                            label = { Text(when (mode) {
                                "SELF_DRIVE" -> "自驱回合"
                                "REACT" -> "智能执行"
                                else -> "静态步骤" }) },
                            modifier = Modifier.fillMaxWidth().minTouchTarget(),
                            enabled = !state.creating
                        )
                    }
                }
                Text(
                    when (d.execMode) {
                        "SELF_DRIVE" -> "按时进入自主回合，结合当前对话与可用工具处理任务。需启用自驱动回合。"
                        "REACT" -> "根据每一步的结果选择下一步，直到完成任务。"
                        else -> "按下方确认的步骤依次执行。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("调度方式", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("MANUAL" to "手动", "DAILY" to "每日", "INTERVAL" to "间隔").forEach { (type, label) ->
                        FilterChip(
                            selected = d.scheduleType == type,
                            onClick = { viewModel.updateDraft(scheduleType = type) },
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth().minTouchTarget(),
                            enabled = !state.creating
                        )
                    }
                }
                when (d.scheduleType) {
                    "DAILY" -> WheelTimeField("执行时间", d.scheduleValue, !state.creating) {
                        viewModel.updateDraft(scheduleValue = it)
                    }
                    "INTERVAL" -> OutlinedTextField(
                        value = d.scheduleValue,
                        onValueChange = { viewModel.updateDraft(scheduleValue = it) },
                        label = { Text("间隔（分钟）") },
                        placeholder = { Text("30") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !state.creating
                    )
                }

                Text("计划步骤", style = MaterialTheme.typography.titleMedium)
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (d.steps.isEmpty()) {
                            Text("尚无固定步骤", style = MaterialTheme.typography.bodyMedium)
                        }
                        d.steps.forEach { s ->
                            Text(s, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.discardDraft() },
                        enabled = !state.creating,
                        modifier = Modifier.fillMaxWidth().minTouchTarget()
                    ) { Text("重新描述") }

                    Button(
                        onClick = { viewModel.confirmCreateDraft(onCreateDone) },
                        enabled = !state.creating,
                        modifier = Modifier.fillMaxWidth().minTouchTarget()
                    ) {
                        if (state.creating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("创建中…")
                        } else {
                            Text("确认创建")
                        }
                    }
                }
            }
        }
    }
}
