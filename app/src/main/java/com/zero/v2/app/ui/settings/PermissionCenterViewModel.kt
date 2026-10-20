package com.zero.v2.app.ui.settings

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.shizuku.ShizukuBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

/** 授权状态 */
enum class PermStatus { GRANTED, DENIED }

/** 直跳目标（各系统特殊授权页） */
enum class JumpTarget {
    ACCESSIBILITY, NOTIFICATION_LISTENER, USAGE_ACCESS, EXACT_ALARM, APP_DETAILS, OVERLAY_WINDOW, DEVELOPER_OPTIONS,
    // M2.4（B-II-11）：Termux 保活四项
    TERMUX_BATTERY, TERMUX_BOOT, TERMUX_AUTOSTART
}

/** 权限条目（ROADMAP §一矩阵的 UI 投影） */
data class PermissionItem(
    val key: String,
    val icon: String,
    val title: String,
    val description: String,
    val status: PermStatus,
    val jump: JumpTarget,
    /** 通知运行时权限走屏幕内 launcher，不走 Intent 直跳 */
    val runtimeRequest: Boolean = false
)

/** 模块权限声明（原 5.3 ModulePermission 内容） */
data class ModulePermDecl(
    val moduleId: String,
    val displayName: String,
    val permissions: List<String>
)

data class PermissionCenterState(
    val items: List<PermissionItem> = emptyList(),
    val moduleDecls: List<ModulePermDecl> = emptyList(),
    /** S8.3（图纸 D4 · 裁定 10）：通道状态——微信登录态（null=探测中/未知） */
    val wechatLoggedIn: Boolean? = null,
    /** E1.4：云服区——扫码登录 */
    val imLoginUrl: String = "",
    val imLoginPolling: Boolean = false,
    val imLoginMessage: String? = null,
    val channelError: String? = null,
    /** E1.4：云服区——参数（im_config_get/set） */
    val imConfig: ImConfigState = ImConfigState(),
    /** M2.4（B-II-11）：Termux 保活四项——模块化基建（A1 §12.6） */
    val termux: TermuxSectionState = TermuxSectionState()
)

/** Termux 工作站保活状态（A1：电池优化/开机自启/OEM 白名单/wake-lock） */
data class TermuxSectionState(
    val installed: Boolean = false,
    val batteryIgnored: Boolean = false,
    val bootInstalled: Boolean = false,
    /** wake-lock 持有（null=未知——Shizuku 未激活时 dumpsys 不可读） */
    val wakeLockHeld: Boolean? = null,
    val busy: Boolean = false,
    /** M4.1（A-II-3）：跨会话崩数超 K 自动停的模块（空=无告警） */
    val autoDisabled: List<String> = emptyList()
)

/**
 * 权限中心 ViewModel — Step 5.3（v3.3 扩容）。
 * 状态自检（AppOps/Secure/NotificationManager）+ 各系统授权页 Intent 直跳
 * （修复三星韩版等 ROM 第三方特殊授权入口不可达问题）。
 * S8.3：通道状态（微信登录态）统一在本页引导管理（裁定 10）。
 */
@HiltViewModel
class PermissionCenterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moduleManager: ModuleLifecycleManager,
    private val traceManager: AgentTraceManager,
    private val shizukuBridge: ShizukuBridge,  // M1.2：Shizuku 系统操作层状态自检
    private val termuxBridge: com.zero.v2.services.termux.TermuxBridge,  // M2.4：Termux 保活四项
    private val dynamicModuleDao: com.zero.v2.core.data.dao.DynamicModuleDao,  // M4.1：崩溃自动停告警
    private val cloud: PermissionCloudCall,
    savedStateHandle: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle()
) : ViewModel() {

    private val _state = MutableStateFlow(PermissionCenterState())
    val state: StateFlow<PermissionCenterState> = _state.asStateFlow()

    private val cloudController = PermissionCloudController(viewModelScope, savedStateHandle, cloud)

    init {
        viewModelScope.launch { cloudController.state.collect { remote ->
            _state.update { it.copy(imConfig = remote.config, wechatLoggedIn = remote.loggedIn,
                imLoginUrl = remote.loginUrl, imLoginPolling = remote.loginBusy,
                imLoginMessage = remote.loginMessage, channelError = remote.statusError) }
        } }
        refresh(); probeChannels(); loadImConfig()
    }

    fun probeChannels() = cloudController.probe()
    fun startImLogin() = cloudController.startLogin()
    fun cancelImLogin() = cloudController.cancelLogin()
    fun loadImConfig() = cloudController.load()
    fun saveImConfig() = cloudController.save()
    fun cancelImConfig() = cloudController.cancelConfig()
    fun editImConfig(whitelist: String, cap: String, autoForward: Boolean) =
        cloudController.edit(whitelist, cap, autoForward)
    fun updateImConfig(whitelist: String, dailyCap: Int, autoForward: Boolean) =
        editImConfig(whitelist, dailyCap.toString(), autoForward)

    /** 授权状态自检（进入本屏 / 手动刷新 / 授权返回后调用） */
    fun refresh() {
        val items = listOf(
            PermissionItem(
                key = "accessibility", icon = "♿",
                title = "无障碍服务",
                description = "用于屏幕文字阅读和手势控制，也可辅助后台恢复。" +
                    "自主运行由后台宿主维持，无需为保活反复开关此权限。",
                status = if (isAccessibilityEnabled()) PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.ACCESSIBILITY
            ),
            // M1.2（模块化轨 D63）：Shizuku 系统操作层——tap/swipe/系统动作特权执行
            PermissionItem(
                key = "shizuku", icon = "⚙️",
                title = "系统操作（Shizuku）",
                description = "用于点击、滑动和系统操作。通过无线调试启动 Shizuku 后返回 Zero 授权；" +
                    "未连接时，手势操作可使用已启用的无障碍服务。",
                status = if (shizukuBridge.isReady) PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.DEVELOPER_OPTIONS
            ),
            PermissionItem(
                key = "usage_access", icon = "📊",
                title = "使用情况访问",
                description = "查看屏幕使用时长和应用使用统计。若系统应用列表未显示入口，可从这里前往授权页。",
                status = if (isUsageAccessGranted()) PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.USAGE_ACCESS
            ),
            PermissionItem(
                key = "notifications", icon = "🔔",
                title = "通知",
                description = "显示提醒和自动化结果通知。可在系统设置中管理通知权限和通知类别。",
                status = if (NotificationManagerCompat.from(context).areNotificationsEnabled())
                    PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.APP_DETAILS,
                runtimeRequest = true
            ),
            PermissionItem(
                key = "exact_alarm", icon = "⏰",
                title = "精确闹钟（定时触发）",
                description = "为提醒和定时任务设置精确闹钟。未授权时使用时间窗口，系统休眠可能推迟送达。",
                status = if (isExactAlarmGranted()) PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.EXACT_ALARM
            ),
            PermissionItem(
                key = "notification_listener", icon = "📥",
                title = "通知感知",
                description = "读取所选应用的通知；感知范围在设置的事件感知中管理",
                status = if (NotificationManagerCompat.getEnabledListenerPackages(context)
                        .contains(context.packageName)) PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.NOTIFICATION_LISTENER
            ),
            // 6.6b: Live 语音所需权限补全（麦克风 + 悬浮窗）；MediaProjection 会话制弹窗不设常驻项
            PermissionItem(
                key = "microphone", icon = "🎙",
                title = "麦克风",
                description = "用于 Live 语音识别与对话，进入 Live 时按需请求授权。",
                status = if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.APP_DETAILS
            ),
            PermissionItem(
                key = "overlay_window", icon = "悬浮",
                title = "悬浮窗",
                description = "用于 Live 后台悬浮球和控制菜单。未授权时可使用通知栏控制。",
                status = if (Settings.canDrawOverlays(context)) PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.OVERLAY_WINDOW
            ),
            // Step 9.2 (D58): 位置权限（key 用 perm_ 前缀——与 LocationModule.metadata.id "location"
            // 在同一 LazyColumn 中冲突，Compose 要求全局唯一 key）
            PermissionItem(
                key = "perm_location", icon = "📍",
                title = "位置",
                description = "用于查询当前位置、地图路线与周边地点。",
                status = if (context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.APP_DETAILS
            ),
            // Step 9.3 (D58): 日历权限
            PermissionItem(
                key = "perm_calendar", icon = "📅",
                title = "日历",
                description = "用于管理系统日程，读取和写入受各自的系统授权控制。",
                status = if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    PermStatus.GRANTED else PermStatus.DENIED,
                jump = JumpTarget.APP_DETAILS
            )
        )
        val decls = moduleManager.getAll()
            .filter { it.requiredPermissions.isNotEmpty() }
            .map { ModulePermDecl(it.metadata.id, it.metadata.displayName, it.requiredPermissions) }
        // M2.4：Termux 保活四项（同步态 + 异步 wake-lock 探测）
        refreshTermuxSection()
        _state.update { it.copy(items = items, moduleDecls = decls) }
        traceManager.record("ui_permission", NodeType.SYSTEM_EVENT, "settings",
            "status_refresh: granted=${items.count { it.status == PermStatus.GRANTED }}/${items.size}")
    }

    /** Termux 保活状态自检（B-II-11 四项徽标；wake-lock 经 Shizuku dumpsys 异步探测）。 */
    private fun refreshTermuxSection() {
        val installed = termuxBridge.isTermuxInstalled()
        val battery = try {
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(TermuxBoot.PKG)
        } catch (_: Exception) { false }
        val boot = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(TermuxBoot.BOOT_PKG, 0) != null
        } catch (_: Exception) { false }
        _state.update { it.copy(termux = it.termux.copy(
            installed = installed, batteryIgnored = battery, bootInstalled = boot)) }
        viewModelScope.launch {
            val held = probeTermuxWakeLock()
            // M4.1：崩溃自动停告警清单（A-II-3 全局层—— DISABLED 且 crash_count>0）
            val autoDisabled = try {
                dynamicModuleDao.getAutoDisabled().map { it.id }
            } catch (_: Exception) { emptyList() }
            _state.update { s -> s.copy(termux = s.termux.copy(wakeLockHeld = held, autoDisabled = autoDisabled)) }
        }
    }

    /** dumpsys power 判 Termux wake-lock 持有（Shizuku 特权读；未激活/失败=null 未知态）。 */
    private suspend fun probeTermuxWakeLock(): Boolean? {
        if (!shizukuBridge.isReady) return null
        return try {
            val out = shizukuBridge.runShell(
                "dumpsys power | grep -iEc 'wake_lock.*termux|termux.*wake_lock'") ?: return null
            // runShell 输出形如 "exit=0<N>"——exit=0 且计数>0 视为持有
            val body = out.substringAfter("exit=0", "")
            body.trim().toIntOrNull()?.let { it > 0 }
                ?: (out.contains("exit=0") && body.isNotBlank())
        } catch (_: Exception) { null }
    }

    /** M2.4④：释放 Termux wake-lock（§12.6.1 纪律——全禁用时 release 的用户手动位）。 */
    fun releaseTermuxWakeLock() {
        viewModelScope.launch {
            _state.update { it.copy(termux = it.termux.copy(busy = true)) }
            try {
                val ok = termuxBridge.releaseWakeLock()
                traceManager.record("ui_permission", NodeType.SYSTEM_EVENT, "settings",
                    "termux_wakelock_release: sent=$ok")
            } finally {
                _state.update { it.copy(termux = it.termux.copy(busy = false)) }
                refreshTermuxSection()
            }
        }
    }

    /** 构建 Intent 并埋点（通知运行时权限除外 — 屏幕内 launcher 处理） */
    fun jumpIntent(target: JumpTarget): Intent {
        traceManager.record("ui_permission", NodeType.SYSTEM_EVENT, "settings",
            "jump: target=$target")
        return when (target) {
            JumpTarget.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            JumpTarget.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            JumpTarget.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            JumpTarget.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", context.packageName, null))
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null))
            JumpTarget.APP_DETAILS -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
            // 6.6b: 悬浮窗特殊授权直跳（带包名定位到本应用）
            JumpTarget.OVERLAY_WINDOW -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", context.packageName, null)
            )
            // M1.2: Shizuku 激活入口——开发者选项（无线调试在此开启）；开发者选项未开时直跳会抛
            // ActivityNotFoundException → 回退本应用详情页（无 Screen 层 try/catch，须在 VM 内兜底）
            JumpTarget.DEVELOPER_OPTIONS -> {
                val devEnabled = try {
                    Settings.Global.getInt(
                        context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
                } catch (_: Exception) { true }
                if (devEnabled) Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null))
            }
            // M2.4①（B-II-11）：替 Termux 请求豁免电池优化（需 manifest
            // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS；部分 ROM 拒三方替包请求→Screen 兜底列表页）
            JumpTarget.TERMUX_BATTERY -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", TermuxBoot.PKG, null))
            // M2.4②：Termux:Boot 已装→其应用详情（查收自启）；缺失→F-Droid 引导页
            JumpTarget.TERMUX_BOOT -> {
                val bootInstalled = try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(TermuxBoot.BOOT_PKG, 0) != null
                } catch (_: Exception) { false }
                if (bootInstalled) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", TermuxBoot.BOOT_PKG, null))
                else Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://f-droid.org/packages/com.termux.boot/"))
            }
            // M2.4③：OEM 自启动白名单（厂商管理器逐一探测；无匹配/三星韩版→Termux 应用详情兜底）
            JumpTarget.TERMUX_AUTOSTART -> {
                val intent = TermuxBoot.AUTOSTART_COMPONENTS.firstNotNullOfOrNull { (pkg, cls) ->
                    val i = Intent().setClassName(pkg, cls)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (context.packageManager.resolveActivity(i, 0) != null) i else null
                }
                intent ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", TermuxBoot.PKG, null))
            }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ═══ 状态自检 ═══

    private fun isAccessibilityEnabled(): Boolean = try {
        Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("${context.packageName}/") == true
    } catch (_: Exception) { false }

    private fun isUsageAccessGranted(): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    /** v3.4 (D40): 精确闹钟授权（12+；以下版本系统默认允许） */
    private fun isExactAlarmGranted(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                .canScheduleExactAlarms()
        } else true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { false }
}

/** M2.4（B-II-11）：Termux 保活四项常量 */
private object TermuxBoot {
    const val PKG = "com.termux"
    const val BOOT_PKG = "com.termux.boot"

    /** 厂商自启动管理页（§12.6.2 常见入口；三星无独立页→兜底 Termux 应用详情——韩版实测路径） */
    val AUTOSTART_COMPONENTS = listOf(
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.lenovo.security" to "com.lenovo.security.permission.appstart.PermissionAppStartActivity"
    )
}
