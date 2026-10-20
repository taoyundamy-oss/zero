package com.zero.v2.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import kotlinx.coroutines.launch

/**
 * 权限中心 — Step 5.3（v3.3 扩容）。
 * 全量权限矩阵 + 状态徽标 + 各系统授权页一键直跳（修复特殊授权入口不可达）
 * + 模块权限声明区（原 ModulePermission 内容）。
 * 从系统授权页返回时自动刷新状态（Lifecycle ON_RESUME）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PermissionCenterScreen(
    onBack: () -> Unit,
    viewModel: PermissionCenterViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmBack by remember { mutableStateOf(false) }
    fun requestBack() {
        if (state.imConfig.dirty || state.imConfig.saving || state.imLoginPolling) confirmBack = true
        else onBack()
    }
    BackHandler(enabled = state.imConfig.dirty || state.imConfig.saving || state.imLoginPolling) { requestBack() }
    if (confirmBack) AlertDialog(
        onDismissRequest = { confirmBack = false },
        title = { Text("返回前确认") },
        text = { Text(if (state.imConfig.saving)
            "停止等待不会撤回远端保存。未保存的编辑将被放弃，返回后请重新读取核对。"
            else "返回将放弃未保存的编辑，并停止登录等待。") },
        confirmButton = { TextButton(onClick = {
            viewModel.cancelImConfig(); viewModel.cancelImLogin(); confirmBack = false; onBack()
        }) { Text("放弃并返回") } },
        dismissButton = { TextButton(onClick = { confirmBack = false }) { Text("保留编辑") } },
    )
    fun showLaunchFailure() {
        scope.launch { snackbar.showSnackbar(
            "无法打开此页面，请稍后重试或从系统设置中查找。", withDismissAction = true) }
    }
    fun openPage(intent: android.content.Intent, fallback: android.content.Intent? = null) {
        if (!openPermissionDestination(context, intent, fallback)) showLaunchFailure()
    }

    // 通知运行时权限（Android 13+）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { viewModel.refresh() }

    // 从系统授权页返回 → 自检刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ZeroPageHeader("系统权限", { requestBack() }) {
                IconButton(onClick = { viewModel.refresh(); viewModel.probeChannels() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新状态")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.items, key = { it.key }) { item ->
                PermissionCard(
                    item = item,
                    onJump = {
                        if (item.runtimeRequest) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                try { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                catch (_: IllegalStateException) { showLaunchFailure() }
                            } else {
                                openPage(viewModel.jumpIntent(item.jump))
                            }
                        } else {
                            openPage(viewModel.jumpIntent(item.jump))
                        }
                    }
                )
            }

            // ═══ S8.3（图纸 D4 · 裁定 10）：通道状态——微信登录态等统一由权限中心引导管理 ═══
            item {
                Spacer(Modifier.height(12.dp))
                Text("通道状态", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
            }
            item {
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("微信通道", style = MaterialTheme.typography.titleMedium)
                            val (label, container) = when (state.wechatLoggedIn) {
                                true -> "已连接" to MaterialTheme.colorScheme.primaryContainer
                                false -> "未连接" to MaterialTheme.colorScheme.errorContainer
                                null -> "状态未知" to MaterialTheme.colorScheme.surfaceVariant
                            }
                            Surface(shape = MaterialTheme.shapes.small, color = container,
                                contentColor = when (state.wechatLoggedIn) {
                                    true -> MaterialTheme.colorScheme.onPrimaryContainer
                                    false -> MaterialTheme.colorScheme.onErrorContainer
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                }) {
                                Text(label, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (state.wechatLoggedIn == true)
                                "可使用微信通道发送消息，送达方式按实际设置选择。"
                            else "连接后可使用微信通道；未连接时仍可在应用内查看消息。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // E1.4（设计 §2.3）：扫码登录——im_login 取链接，浏览器打开由微信接管确认
                        if (state.wechatLoggedIn != true) {
                            Spacer(Modifier.height(8.dp))
                            if (state.imLoginUrl.isBlank()) {
                                OutlinedButton(onClick = { viewModel.startImLogin() },
                                    enabled = !state.imLoginPolling) {
                                    Text(if (state.imLoginPolling) "等待微信确认…" else "发起微信登录")
                                }
                            } else {
                                Text("登录链接已生成——点击用微信打开并确认：",
                                    style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(4.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        openPage(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(state.imLoginUrl)))
                                    }) { Text("打开登录链接") }
                                    OutlinedButton(onClick = { viewModel.startImLogin() }, enabled = !state.imLoginPolling) {
                                        Text("重新生成")
                                    }
                                }
                            }
                        }
                        state.channelError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium) }
                        state.imLoginMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (state.imLoginPolling) TextButton(onClick = { viewModel.cancelImLogin() }) {
                            Text("停止等待")
                        }
                    }
                }
                // QQ 通道位：本地 qqbot 模块（D6）——模块配置页管理，此区不占位
            }

            // ═══ E1.4（设计 §2.3）：云服参数区——im_config_get/set ═══
            item {
                Spacer(Modifier.height(8.dp))
                Text("云服通道参数", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
            }
            item {
                val c = state.imConfig
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("转发白名单（逗号分隔微信 ID，空=不限）",
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = c.whitelist, onValueChange = { viewModel.editImConfig(it, c.capText, c.autoForward) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            placeholder = { Text("如 wxid_aaa, wxid_bbb", style = MaterialTheme.typography.bodyMedium) })
                        Text("每日转发上限（条）", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(value = c.capText, onValueChange = { viewModel.editImConfig(c.whitelist, it, c.autoForward) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("自动转发开关", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Switch(checked = c.autoForward, onCheckedChange = {
                                viewModel.editImConfig(c.whitelist, c.capText, it)
                            })
                        }
                        c.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium) }
                        c.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (c.loading) Text("正在读取参数…", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.saveImConfig() }, enabled = c.loaded && !c.loading && !c.saving) {
                                Text(if (c.saving) "保存中…" else "保存参数")
                            }
                            OutlinedButton(onClick = { viewModel.loadImConfig() }, enabled = !c.loading && !c.saving) {
                                Text(if (c.error != null || !c.loaded) "重试读取" else "重新读取")
                            }
                            if (c.loading || c.saving) TextButton(onClick = { viewModel.cancelImConfig() }) {
                                Text("停止等待参数")
                            }
                        }
                    }
                }
            }

            // ═══ M2.4（B-II-11）：Termux 工作站保活四项（A1 §12.6）═══
            item {
                Spacer(Modifier.height(12.dp))
                Text("Termux 工作站", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
            }
            item {
                val t = state.termux
                val canJump = t.installed
                ZeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!t.installed) {
                            Text("安装 Termux 后可运行本地动态模块。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error)
                        }
                        // ① 电池优化豁免（替 com.termux 请求）
                        TermuxPermRow(
                            title = "电池优化豁免",
                            badge = if (t.batteryIgnored) "已豁免" else "受电池优化限制",
                            granted = t.batteryIgnored,
                            enabled = canJump,
                            actionLabel = "直达",
                            onAction = {
                                // 部分 ROM 拒三方替包请求（SecurityException）→ 兜底电池优化白名单列表页
                                openPage(viewModel.jumpIntent(JumpTarget.TERMUX_BATTERY),
                                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            })
                        // ② Termux:Boot 开机自启
                        TermuxPermRow(
                            title = "Termux:Boot（开机自启）",
                            badge = if (t.bootInstalled) "已安装" else "未安装",
                            granted = t.bootInstalled,
                            enabled = true,   // 未装也可点（引导 F-Droid）
                            actionLabel = if (t.bootInstalled) "详情" else "安装引导",
                            onAction = { openPage(viewModel.jumpIntent(JumpTarget.TERMUX_BOOT)) })
                        // ③ OEM 自启动白名单
                        TermuxPermRow(
                            title = "系统自启动设置",
                            badge = "需手动检查",
                            granted = null,
                            enabled = canJump,
                            actionLabel = "直达",
                            onAction = { openPage(viewModel.jumpIntent(JumpTarget.TERMUX_AUTOSTART)) })
                        Text("厂商管理器内把 Termux/Termux:Boot 加入自启动（无独立页的 ROM 在 Termux 应用详情手动允许）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // ④ wake-lock 持有状态 + 释放
                        TermuxPermRow(
                            title = "后台唤醒锁",
                            badge = when (t.wakeLockHeld) {
                                true -> "已持有"
                                false -> "未持有"
                                null -> "未知（需系统操作连接）"
                            },
                            granted = t.wakeLockHeld,
                            enabled = !t.busy && t.wakeLockHeld == true,
                            actionLabel = if (t.busy) "…" else "释放",
                            onAction = { viewModel.releaseTermuxWakeLock() })
                        // M4.1（A-II-3 全局层）：跨会话崩数超 K 自动停告警——用户主权入口
                        if (t.autoDisabled.isNotEmpty()) {
                            Surface(shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp)) {
                                    Text("已自动停用：${t.autoDisabled.joinToString("、")}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                    Text("跨会话崩溃计数超阈值已自动停用——去 工作区 → 动态模块 查看并处置（再启用=重新计数）",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            }

            if (state.moduleDecls.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Text("模块权限声明", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                }
                items(state.moduleDecls, key = { it.moduleId }) { decl ->
                    ZeroCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(decl.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                decl.permissions.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Title, status and action wrap independently at large resource font sizes. */
@Composable
private fun TermuxPermRow(
    title: String, badge: String, granted: Boolean?, enabled: Boolean,
    actionLabel: String, onAction: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(badge, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (granted == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAction, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionItem, onJump: () -> Unit) {
    val icon = when (item.key) {
        "accessibility" -> Icons.Outlined.AccessibilityNew
        "usage_access" -> Icons.Outlined.QueryStats
        "notifications", "notification_listener" -> Icons.Outlined.Notifications
        "exact_alarm" -> Icons.Outlined.Alarm
        "microphone" -> Icons.Outlined.Mic
        "overlay_window" -> Icons.Outlined.PictureInPictureAlt
        "perm_location" -> Icons.Outlined.LocationOn
        "perm_calendar" -> Icons.Outlined.CalendarMonth
        else -> Icons.Outlined.Settings
    }
    ZeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface)
                Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            Text(item.description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (item.status == PermStatus.GRANTED) "已授权" else "未授权",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (item.status == PermStatus.GRANTED) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error)
                TextButton(onClick = onJump, modifier = Modifier.heightIn(min = 48.dp)) { Text("前往设置") }
            }
        }
    }
}
