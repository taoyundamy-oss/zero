package com.zero.v2.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.core.data.dao.UsageBucket
import com.zero.v2.core.data.entity.UsageRequestEntity
import com.zero.v2.app.ui.date.WheelDateField
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val HitColor = Color(0xFF87CEFA)
private val MissColor = Color(0xFF479DF5)
private val OutputColor = Color(0xFF0866E8)
private val UnknownColor = Color(0xFF8995A5)
private fun count(value: Long) = String.format(java.util.Locale.getDefault(), "%,d", value)

@Composable
fun UsageScreen(onBack: () -> Unit, viewModel: UsageViewModel = hiltViewModel(),
    budgetModel: AgentBehaviorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val budget by budgetModel.state.collectAsState()
    var custom by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        while (true) {
            viewModel.refreshCalendar()
            kotlinx.coroutines.delay(30_000)
        }
    }
    Scaffold(topBar = { ZeroPageHeader("成本与用量", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "今日", 7 to "近7天", 30 to "近30天").forEach { (days, label) ->
                    val today = LocalDate.now()
                    val window = UsageWindow(today.minusDays(days - 1L), today)
                    FilterChip(selected = filter.rollingDays == days, onClick = {
                        viewModel.select(filter.copy(window = window, offset = 0, rollingDays = days))
                    }, label = { Text(label) })
                }
                OutlinedButton(onClick = { custom = true }) { Text("自定义") }
                TextButton(onClick = viewModel::refresh) { Text("刷新") }
            }
            Text("${filter.window.first} — ${filter.window.last} · ${filter.window.zone.id}",
                style = MaterialTheme.typography.bodySmall)
            UsageChoice("连接", filter.connection, state.routes.map { it.connectionId }.distinct()) {
                viewModel.select(filter.copy(connection = it, model = "", offset = 0))
            }
            UsageChoice("模型", filter.model, state.routes.filter {
                filter.connection.isBlank() || it.connectionId == filter.connection
            }.map { it.model }.distinct()) { viewModel.select(filter.copy(model = it, offset = 0)) }
            when {
                state.loading -> CircularProgressIndicator()
                state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
                else -> {
                    if (state.incomplete) Text("记录曾写入失败，统计可能不完整。", color = MaterialTheme.colorScheme.error)
                    val total = UsageReport.total(state.buckets)
                    UsageOverview(total)
                    if (total.requests == 0L) Text("所选期间暂无请求记录。新版启用后开始累计，历史记录未回填。")
                    else {
                        UsageTimeChart(UsageReport.chart(state.buckets, filter.window), filter.window)
                        UsageEventChart(state.details, filter.window)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(enabled = filter.offset > 0, onClick = {
                                viewModel.select(filter.copy(offset = (filter.offset - 500).coerceAtLeast(0)))
                            }) { Text("较新请求") }
                            TextButton(enabled = filter.offset + 500 < total.requests, onClick = {
                                viewModel.select(filter.copy(offset = filter.offset + 500))
                            }) { Text("更早请求") }
                        }
                        Text("事件图：从最新起第${filter.offset + 1}—${filter.offset + state.details.size}条，页内按时间排列；总览与柱图包含筛选期间全部记录。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            UsageBudget(budget, budgetModel, viewModel.legacyBudget(), viewModel.pricingSummary())
            Text("统计范围：文本流式、同步及有界补全的应用层尝试。媒体与向量调用暂未纳入；未结束请求不视为零用量。累计缓存命中量不代表服务商当前缓存容量。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (custom) UsageDateDialog(filter.window, onDismiss = { custom = false }) {
        viewModel.select(filter.copy(window = it, offset = 0, rollingDays = null)); custom = false
    }
}

@Composable
private fun UsageChoice(label: String, selected: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label：${selected.ifBlank { "全部" }}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            (listOf("") + choices).forEach { value ->
                DropdownMenuItem(text = { Text(value.ifBlank { "全部" }) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}

@Composable
internal fun UsageOverview(total: UsageBucket) {
    ZeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已知 Tokens ${count(total.input + total.output)}", style = MaterialTheme.typography.titleLarge)
            Text("缓存命中率 ${UsageReport.percent(total.cached, total.observedInput)}", style = MaterialTheme.typography.titleMedium)
            Text("输入 ${count(total.input)} · 输出 ${count(total.output)}")
            Text("命中输入 ${count(total.cached)} · 未命中输入 ${count(total.observedInput - total.cached)}")
            Text("缓存情况未知的输入 ${count(total.input - total.observedInput)}")
            Text("已知输入覆盖率 ${UsageReport.percent(total.observedInput, total.input)} · 请求覆盖率 ${UsageReport.percent(total.knownCache, total.requests)}")
            Text("${total.requests} 次尝试 · 缓存未知 ${total.requests - total.knownCache} · 输入未知 ${total.unknownInput}\n" +
                "未结束 ${total.unfinished} · 失败或取消 ${total.failed}")
            Text("费用：${if (total.requests == 0L) "暂无记录" else if (total.localRequests == total.requests) "本地调用，未估算设备成本" else "区间费用未核算（模型价格累计见下方月度预算，不追溯旧记录）"}")
        }
    }
}

@Composable
internal fun UsageTimeChart(buckets: List<UsageBucket>, window: UsageWindow) {
    var selection by remember(buckets) { mutableIntStateOf(-1) }
    val max = buckets.maxOfOrNull { it.input + it.output }?.coerceAtLeast(1) ?: 1
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val firstData = buckets.indexOfFirst { it.requests > 0 }.coerceAtLeast(0)
    LaunchedEffect(window, firstData) {
        scroll.scrollTo(with(density) { (firstData * 52).dp.roundToPx() })
    }
    val formatter = DateTimeFormatter.ofPattern(if (window.first == window.last) "HH:mm" else "MM-dd")
    fun label(row: UsageBucket) = Instant.ofEpochMilli(row.bucket).atZone(window.zone).format(formatter)
    Text("随时间变化的用量", style = MaterialTheme.typography.titleMedium)
    Text("浅蓝 命中 · 蓝 未命中 · 深蓝 输出 · 灰 缓存未知", style = MaterialTheme.typography.bodySmall)
    Text("纵轴 0 — ${count(max)} tokens；横向滚动并点选柱查看详情", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        buckets.forEachIndexed { index, row ->
            Column(Modifier.width(48.dp).clickable { selection = index }.semantics {
                contentDescription = "${label(row)}，输入${row.input}，命中${row.cached}，输出${row.output}"
            }) {
                Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                    var y = size.height
                    listOf(row.cached to HitColor, row.observedInput - row.cached to MissColor,
                        row.input - row.observedInput to UnknownColor, row.output to OutputColor).forEach { (value, color) ->
                        val height = size.height * (value.toDouble() / max).toFloat()
                        y -= height
                        drawRect(color, Offset(10f, y), Size(size.width - 20f, height))
                    }
                    drawLine(UnknownColor, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }
                Text(label(row), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    buckets.getOrNull(selection)?.let { row ->
        Text("${Instant.ofEpochMilli(row.bucket).atZone(window.zone)}\n命中 ${count(row.cached)} · 未命中 ${count(row.observedInput - row.cached)}\n" +
            "缓存未知输入 ${count(row.input - row.observedInput)} · 输出 ${count(row.output)} · ${row.requests} 次请求")
    }
}

@Composable
internal fun UsageEventChart(rows: List<UsageRequestEntity>, window: UsageWindow) {
    var selection by remember(rows) { mutableIntStateOf(-1) }
    Text("随请求与事件变化的缓存命中率", style = MaterialTheme.typography.titleMedium)
    Text("纵轴 0—100%；灰色短线为未知，未知处不连线。点选请求查看事件。", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { index, row ->
            val ratio = row.cachedTokens?.let { cached -> row.inputTokens?.takeIf { it > 0 }?.let { cached.toFloat() / it } }
            val previous = rows.getOrNull(index - 1)?.let { before -> before.cachedTokens?.let { hit ->
                before.inputTokens?.takeIf { it > 0 }?.let { hit.toFloat() / it }
            } }
            Column(Modifier.width(48.dp).clickable { selection = index }.semantics {
                contentDescription = "请求${index + 1}，命中率${if (ratio == null) "未知" else "${ratio * 100}%"}，${event(row, rows.getOrNull(index - 1))}"
            }) {
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    if (ratio == null) drawLine(UnknownColor, Offset(size.width / 2 - 5, size.height / 2), Offset(size.width / 2 + 5, size.height / 2), 3f)
                    else {
                        val point = Offset(size.width / 2, 5f + (size.height - 10f) * (1 - ratio))
                        if (previous != null) drawLine(OutputColor,
                            Offset(-size.width / 2, 5f + (size.height - 10f) * (1 - previous)), point, 2f)
                        drawCircle(OutputColor, 5f, point)
                    }
                }
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    rows.getOrNull(selection)?.let { row ->
        Text("${Instant.ofEpochMilli(row.startedAt).atZone(window.zone)}\n${row.model} · ${row.kind} · ${row.status}\n" +
            "输入 ${row.inputTokens?.let(::count) ?: "未知"} · 命中 ${row.cachedTokens?.let(::count) ?: "未知"} · 输出 ${row.outputTokens?.let(::count) ?: "未知"}\n" +
            event(row, rows.getOrNull(selection - 1)) + "\n输入估算：${when(row.budgetSource) {
                "observed_prefix" -> "已观测前缀加新增内容"
                "no_observation" -> "无历史用量，使用保守估算"
                "header_changed" -> "设置变化，使用保守估算"
                "history_changed", "history_shortened" -> "历史变化，使用保守估算"
                else -> "保守估算"
            }}")
    }
}

private fun event(row: UsageRequestEntity, previous: UsageRequestEntity?): String = buildList {
    add("事件：" + row.continuity.split("/").joinToString("、") { reason -> when (reason) {
        "generation_switch" -> "世代切换"
        "scope_changed" -> "模型或稳定前缀变化"
        "anchor_missing" -> "历史锚点缺失"
        "append_only" -> "稳定追加"
        "first_request" -> "该作用域首次记录"
        "history_changed" -> "历史变化"
        "tools_or_settings_changed" -> "工具或设置变化"
        "independent" -> "独立补全"
        else -> "未知"
    } })
    if (previous != null && row.model != previous.model) add("模型变化")
    if (previous != null) add("距上一条开始 ${((row.startedAt - previous.startedAt) / 60000)} 分钟（非服务端TTL判定）")
}.joinToString(" · ")

@Composable
private fun UsageBudget(state: AgentBehaviorState, model: AgentBehaviorViewModel, legacy: Triple<Long, Long, Double>, pricing: String) {
    Text("预算保护", style = MaterialTheme.typography.titleMedium)
    if (!state.behaviorLoaded) {
        Text(state.behaviorError ?: "正在读取预算设置")
        TextButton(onClick = model::reloadBehavior) { Text("重试读取") }
    }
    state.behaviorError?.let { Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = model::retryBehaviorSave) { Text("重试保存") } }
    if (state.behaviorSaving) Text("正在保存预算")
    ZeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SliderRow("每日 Token 预算", state.dailyTokenLimit.toFloat(), 8_000_000f..100_000_000f, "tokens",
                state.behaviorLoaded && !state.behaviorSaving) { model.setBudget(dailyTokenLimit = it.toLong()) }
            SliderRow("月费上限", state.monthlyCostLimitUsd, 5f..50f, "美元",
                state.behaviorLoaded && !state.behaviorSaving) { model.setBudget(monthlyCostLimitUsd = it) }
            Text(pricing)
            Text("原预算累计：今日 ${count(legacy.first)} tokens；本月 ${count(legacy.second)} tokens。")
            Text("月度保护累计：$${java.math.BigDecimal.valueOf(legacy.third).stripTrailingZeros().toPlainString()}。已定价请求按请求时价格累计，未知部分沿用历史混合费率保护；不代表本页筛选费用或真实账单。")
        }
    }
}

@Composable
private fun UsageDateDialog(window: UsageWindow, onDismiss: () -> Unit, onSelect: (UsageWindow) -> Unit) {
    var firstDay by rememberSaveable { mutableLongStateOf(window.first.toEpochDay()) }
    val first = LocalDate.ofEpochDay(firstDay)
    var lastDay by rememberSaveable { mutableLongStateOf(window.last.toEpochDay()) }
    val last = LocalDate.ofEpochDay(lastDay)
    val candidate = runCatching { UsageWindow(first, last, window.zone) }.getOrNull()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义日期") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WheelDateField("开始日期", first) { firstDay = it.toEpochDay() }
            WheelDateField("结束日期", last) { lastDay = it.toEpochDay() }
            Text("包含起止日，最多366天；按设备时区统计。")
            if (candidate == null) Text(if (last.isBefore(first)) "结束日期不能早于开始日期" else "日期范围不能超过366天", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = candidate != null, onClick = { candidate?.let(onSelect) }) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
