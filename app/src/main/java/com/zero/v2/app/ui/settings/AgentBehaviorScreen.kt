package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.ZeroSettingSwitch

/**
 * Agent 自主行为设置页 — Step 5.1（DESIGN §1.3 用户主权：所有定时/频率/策略参数用户可调）。
 * 参数经确认保存后发布到模块 Config；滑杆结束拖动时提交候选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBehaviorScreen(
    onBack: () -> Unit,
    viewModel: AgentBehaviorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { ZeroPageHeader("自主行为", onBack) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!state.behaviorLoaded) {
                Text(state.behaviorError ?: "正在读取自主行为设置", color = MaterialTheme.colorScheme.onSurface)
                if (state.behaviorError != null) {
                    TextButton(onClick = viewModel::reloadBehavior, enabled = !state.behaviorSaving) { Text("重试读取") }
                }
            } else if (state.behaviorError != null) {
                Text(state.behaviorError!!, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retryBehaviorSave, enabled = !state.behaviorSaving) { Text("重试保存与切换") }
            }
            if (state.behaviorSaving) Text("正在保存设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.parameterSaving) Text("正在保存参数", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.parameterError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retryParameterSave, enabled = !state.parameterSaving) { Text("重试保存参数") }
            }
            state.parameterMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // ═══ Agent 模式 ═══
            SectionTitle("运行模式")
            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.selectableGroup()) {
                    listOf("OFF" to "关闭", "SMART" to "智能", "ALWAYS_ON" to "始终在线").forEach { (mode, label) ->
                        val enabled = state.behaviorLoaded && !state.behaviorSaving
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(state.mode == mode, enabled = enabled, role = Role.RadioButton,
                                onClick = { viewModel.setMode(mode) }).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(state.mode == mode, onClick = null, enabled = enabled)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Text(
                if (state.mode == "OFF") "停止报点心跳与报点自驱，重启后保持关闭。已设置的系统闹钟仍按各自规则处理。"
                else "开启后按设定节奏运行，并在用户对话时退避。智能与始终在线目前采用相同的调度与电源策略。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ═══ SD2：报点随发自驱（始终在线自主行动）═══
            SectionTitle("自主行动")
            ZeroCard(Modifier.fillMaxWidth()) {
                ZeroSettingSwitch("报点随发自主行动", "报点时继续当前任务或自主活动。你发消息时保持安静，离场后按波次恢复。次数受每日触发上限约束。",
                    state.selfDriveOnTick, { viewModel.setSelfDriveOnTick(it) }, enabled = state.behaviorLoaded && !state.behaviorSaving)
            }

            // ═══ 报点参数 ═══
            SectionTitle("报点节奏")
            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SliderRow(
                        label = "基础间隔", value = state.baseIntervalSec.toFloat(),
                        range = viewModel.parameterRange("baseIntervalSec"), unit = "秒",
                        enabled = !state.parameterSaving, onChange = { viewModel.setReportParam("baseIntervalSec", it.toInt()) })
                    SliderRow(
                        label = "随机偏移", value = state.randomOffsetSec.toFloat(),
                        range = viewModel.parameterRange("randomOffsetSec"), unit = "秒",
                        enabled = !state.parameterSaving, onChange = { viewModel.setReportParam("randomOffsetSec", it.toInt()) })
                    SliderRow(
                        label = "间隔倍数", value = state.intervalMultiplier,
                        range = viewModel.parameterRange("intervalMultiplier"), unit = "x",
                        enabled = !state.parameterSaving, onChange = { viewModel.setReportParam("intervalMultiplier", it) })
                    SliderRow(
                        label = "递增级数上限", value = state.maxDoublings.toFloat(),
                        range = viewModel.parameterRange("maxDoublings"), unit = "次",
                        enabled = !state.parameterSaving, onChange = { viewModel.setReportParam("maxDoublings", it.toInt()) })
                    SliderRow(
                        label = "在场静默窗", value = state.userPresenceWindowMin.toFloat(),
                        range = viewModel.parameterRange("userPresenceWindowMin"), unit = "分钟",
                        enabled = !state.parameterSaving, onChange = { viewModel.setReportParam("userPresenceWindowMin", it.toInt()) })
                    Text("最近对话后的这段时间保持安静。设为0可关闭额外静默窗；对话时的自动退避仍然有效。参数保存后生效。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ═══ 记忆自主行为 ═══
            SectionTitle("记忆整理")
            ZeroCard(Modifier.fillMaxWidth()) {
                Column {
                    ZeroSettingSwitch("每日记忆整理", "按设定时间检查冲突、归档与整理记忆，会调用模型。",
                        state.enableReflection, { viewModel.setMemoryParam("enableReflection", it) }, enabled = !state.parameterSaving)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.enableReflection) {
                        SliderRow(
                            label = "记忆整理时间", value = state.reflectionHour.toFloat(),
                            range = viewModel.parameterRange("reflectionHour"), unit = "点",
                            enabled = !state.parameterSaving, onChange = { viewModel.setMemoryParam("reflectionHour", it.toInt()) })
                    }
                    SliderRow(
                        label = "图谱提取阈值", value = state.graphExtractionThreshold.toFloat(),
                        range = viewModel.parameterRange("graphExtractionThreshold"), unit = "条",
                        enabled = !state.parameterSaving, onChange = { viewModel.setMemoryParam("graphExtractionThreshold", it.toInt()) })
                    }
                }
            }


        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface)
}

@Composable
internal fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    unit: String, enabled: Boolean = true, onChange: (Float) -> Unit
) {
    var editing by rememberSaveable(label) { mutableStateOf(false) }
    var draft by rememberSaveable(label) { mutableStateOf("") }
    var preview by remember(value) { mutableStateOf(value) }
    LaunchedEffect(value, enabled) { preview = value }
    fun display(number: Float) = "%.2f".format(java.util.Locale.ROOT, number).trimEnd('0').trimEnd('.')
    Column {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            TextButton(enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { contentDescription = "编辑$label" }, onClick = { draft = display(value); editing = true }) {
                Text("${display(value)} $unit", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Slider(
            enabled = enabled,
            value = preview.coerceIn(range),
            onValueChange = { preview = it },
            onValueChangeFinished = { onChange(preview) },
            valueRange = range
        )
    }
    if (editing) {
        val number = draft.toFloatOrNull()
        val valid = number != null && number.isFinite() && number in range
        AlertDialog(onDismissRequest = { if (enabled) editing = false }, title = { Text(label) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { OutlinedTextField(draft, onValueChange = { draft = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "数值：$label" },
                label = { Text("${display(range.start)}–${display(range.endInclusive)} $unit") },
                isError = !valid,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                if (!valid) Text("请输入范围内的数值", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = valid && enabled, onClick = {
                number?.let(onChange); editing = false
            }) { Text("保存") } },
            dismissButton = { TextButton(enabled = enabled, onClick = { editing = false }) { Text("取消") } })
    }
}

