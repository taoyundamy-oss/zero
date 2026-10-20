package com.zero.v2.app.ui.settings

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.core.data.entity.ReminderEntity
import com.zero.v2.services.agent.TriggerHub
import com.zero.v2.services.agent.TriggerHubConfig
import com.zero.v2.services.agent.TriggerWhitelistStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 事件触发设置屏 — A2b（D71 三层白名单 + D76 聚合状态行 + D5 独立路由）。
 *
 * 三层白名单：推荐预置（预勾选）→ 全量可选（已选置顶）→ 基建排除（默认隐藏）。
 * 配置状态与实际运行健康分开，通知接入仍依赖系统权限与宿主状态。
 */
@HiltViewModel
class TriggerSettingsViewModel @Inject constructor(
    private val whitelistStore: TriggerWhitelistStore,
    private val triggerHub: TriggerHub,
    private val systemEventModule: com.zero.v2.services.module.SystemEventModule
) : ViewModel() {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val isRecommended: Boolean
    )

    private val _whitelist = MutableStateFlow<Set<String>>(emptySet())
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _notificationEnabled = MutableStateFlow(true)
    val notificationEnabled: StateFlow<Boolean> = _notificationEnabled.asStateFlow()

    /** S3.3：提醒管理——模块只读流镜像（模块侧全变更后统一刷新） */
    val reminders = systemEventModule.remindersState

    init {
        viewModelScope.launch {
            whitelistStore.packages.collect { _whitelist.value = it }
        }
        _notificationEnabled.value = (triggerHub.config as TriggerHubConfig).notificationTriggerEnabled
        systemEventModule.refreshReminders()
    }

    fun deleteReminder(id: String) = systemEventModule.deleteReminderFromUi(id)
    fun requeueMissed(id: String) = systemEventModule.requeueMissedFromUi(id)

    fun loadApps(packageManager: PackageManager) {
        val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { app ->
                val label = packageManager.getApplicationLabel(app).toString()
                AppEntry(
                    packageName = app.packageName,
                    label = label,
                    isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isRecommended = isRecommendedApp(label, app.packageName)
                )
            }
            .filter { !isInfraApp(it.label, it.packageName) }  // 基建排除
            .sortedBy { it.label.lowercase() }

        // 推荐预置置顶
        _apps.value = installed.sortedByDescending { it.isRecommended }
    }

    fun toggleApp(pkg: String) {
        if (_whitelist.value.contains(pkg)) whitelistStore.remove(pkg)
        else whitelistStore.add(pkg)
    }

    /** 聚合状态（D76） */
    fun aggregateStatus(): String {
        return configurationStatus(_notificationEnabled.value, _whitelist.value.size)
    }

    fun refreshNotificationConfiguration() {
        _notificationEnabled.value = (triggerHub.config as TriggerHubConfig).notificationTriggerEnabled
    }

    companion object {
        fun configurationStatus(enabled: Boolean, selectedCount: Int): String = when {
            !enabled -> "未启用 · 通知感知已关闭"
            selectedCount == 0 -> "待配置 · 尚未选择应用"
            else -> "已配置 · $selectedCount 个应用"
        }

        /** 推荐预置关键词（D71） */
        private val RECOMMENDED_KEYWORDS = listOf(
            "微信", "WeChat", "QQ", "短信", "SMS", "MMS", "Message",
            "支付宝", "Alipay", "银行", "Bank",
            "日历", "Calendar", "日程",
            "邮件", "Email", "Gmail", "Mail",
            "健康", "Health"
        )

        /** 基建排除关键词（D71） */
        private val INFRA_KEYWORDS = listOf(
            "Android Auto", "auto", "投屏", "Cast", "一键多拍", "AI 拍照", "AI拍照",
            "助手", "Assistant", "输入法", "Input", "主题", "Theme", "墙纸", "Wallpaper",
            "系统UI", "System UI", "设置", "Settings", "蓝牙", "Bluetooth", "WLAN", "WiFi",
            "下载", "Download", "文件管理", "File Manager", "相机", "Camera", "Gallery",
            "相册", "时钟", "Clock", "计算器", "Calculator"
        )

        fun isRecommendedApp(label: String, pkg: String): Boolean =
            RECOMMENDED_KEYWORDS.any { label.contains(it, ignoreCase = true) }

        fun isInfraApp(label: String, pkg: String): Boolean =
            INFRA_KEYWORDS.any { label.contains(it, ignoreCase = true) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerSettingsScreen(
    viewModel: TriggerSettingsViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
        ?.onBackPressedDispatcher
    val whitelist by viewModel.whitelist.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val enabled by viewModel.notificationEnabled.collectAsState()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshNotificationConfiguration()
        }
        lifecycle.addObserver(observer)
        viewModel.refreshNotificationConfiguration()
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadApps(context.packageManager)
    }

    TriggerSettingsContent(whitelist, apps, reminders, enabled,
        onBack = { backDispatcher?.onBackPressed() }, onToggle = viewModel::toggleApp,
        onDelete = viewModel::deleteReminder, onRequeue = viewModel::requeueMissed)
}

/** Same renderer for production and isolated acceptance; callbacks retain the existing owners. */
@Composable
fun TriggerSettingsContent(
    whitelist: Set<String>,
    apps: List<TriggerSettingsViewModel.AppEntry>,
    reminders: List<ReminderEntity>,
    notificationEnabled: Boolean,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRequeue: (String) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = { ZeroPageHeader("事件感知", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ═══ 聚合状态行（D76）═══
            item {
                ZeroCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("通知感知", style = MaterialTheme.typography.titleSmall)
                        Text(
                            TriggerSettingsViewModel.configurationStatus(notificationEnabled, whitelist.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text("这里显示配置状态。实际通知接入还取决于系统通知权限和后台运行状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ═══ S3.3 提醒管理区（D118：状态徽标/来源/missed 高亮一键重排）═══
            item {
                Text("提醒管理（${reminders.size}）", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (reminders.isEmpty()) {
                item {
                    Text("暂无提醒——对话中说「5 分钟后提醒我…」即可创建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(reminders, key = { "rem_${it.id}" }) { r ->
                    ReminderRow(
                        reminder = r,
                        onDelete = { onDelete(r.id) },
                        onRequeue = if (r.status == com.zero.v2.core.data.entity.ReminderEntity.STATUS_MISSED)
                            ({ onRequeue(r.id) }) else null)
                }
                item { HorizontalDivider() }
            }

            // ═══ 引导文案 ═══
            item {
                Text(
                    "选择允许感知通知的应用，Zero 会结合当前状态判断是否需要提醒。" +
                            "每日上限等参数位于「能力与模型 → 事件触发」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ═══ 搜索框 ═══
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索应用") },
                    singleLine = true
                )
            }

            // ═══ 推荐预置区 ═══
            val recommended = apps.filter { it.isRecommended && !whitelist.contains(it.packageName) }
            if (searchQuery.isBlank() && recommended.isNotEmpty()) {
                item {
                    Text("推荐", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                items(recommended, key = { "rec_${it.packageName}" }) { app ->
                    AppRow(app, false) { onToggle(app.packageName) }
                }
                item { HorizontalDivider() }
            }

            // ═══ 已选区 ═══
            val selectedApps = apps.filter { whitelist.contains(it.packageName) }
            if (selectedApps.isNotEmpty()) {
                item {
                    Text("已选（${selectedApps.size}）", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                items(selectedApps, key = { "sel_${it.packageName}" }) { app ->
                    AppRow(app, true) { onToggle(app.packageName) }
                }
                item { HorizontalDivider() }
            }

            // ═══ 全量可选区 ═══
            val filtered = if (searchQuery.isBlank()) {
                apps.filter { !it.isRecommended && !whitelist.contains(it.packageName) }
            } else {
                apps.filter {
                    it.label.contains(searchQuery, ignoreCase = true) ||
                            it.packageName.contains(searchQuery, ignoreCase = true)
                }.filter { !whitelist.contains(it.packageName) }
            }
            if (filtered.isNotEmpty()) {
                item {
                    Text("全部应用", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(app, false) { onToggle(app.packageName) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: TriggerSettingsViewModel.AppEntry,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** S3.3（D118）：提醒行——状态徽标（missed 高亮）/标题/时间/来源 + 删除 + missed 一键重排 */
@Composable
private fun ReminderRow(
    reminder: com.zero.v2.core.data.entity.ReminderEntity,
    onDelete: () -> Unit,
    onRequeue: (() -> Unit)?
) {
    val missed = reminder.status == com.zero.v2.core.data.entity.ReminderEntity.STATUS_MISSED
    val done = reminder.status == com.zero.v2.core.data.entity.ReminderEntity.STATUS_DONE
    val detailColor = if (missed) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    ZeroCard(
        containerColor = if (missed) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (missed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            missed -> "⏸ 已错过"
                            done -> "✓ 完成"
                            else -> "⏳ 活跃"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            missed -> MaterialTheme.colorScheme.onErrorContainer
                            done -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (reminder.createdBy == com.zero.v2.core.data.entity.ReminderEntity.CREATED_BY_LLM) "Zero" else "用户",
                        style = MaterialTheme.typography.labelSmall,
                        color = detailColor
                    )
                }
                Text(
                    reminder.title.ifBlank { reminder.remark },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date(reminder.triggerAt)) +
                            (reminder.repeatRule?.let { " · 周期" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = detailColor
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (onRequeue != null) {
                    TextButton(onClick = onRequeue,
                        colors = ButtonDefaults.textButtonColors(contentColor = detailColor)) { Text("重新安排") }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete,
                        contentDescription = "删除提醒",
                        tint = detailColor)
                }
            }
        }
    }
}
