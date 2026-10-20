package com.zero.v2.services.shizuku

import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.IBinder
import android.os.SystemClock
import com.zero.v2.core.bus.SystemBus
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku 操作层桥 —— M1.2（Gate 1；方案 §1.1/§六，D63 权限矩阵 + D38 会话预授权）。
 *
 * 职责：
 *   - 操作 FC ×9（tap/swipe/input_text/press_key/start_app/open_url/grant_perm/set_setting/shell_run）
 *   - Shizuku 激活态检测（getUid() try/catch——binder 死亡时抛 IllegalStateException）
 *   - bindUserService 绑定 SystemActionUserService（跑 Shizuku 特权进程）+ binder 生死监听重连
 *   - tap/swipe 的无障碍 dispatchGesture 兜底（app 层 AgentRuntimeService 注入 gestureDispatcher）
 *   - D38：Live 语音会话内 tap/swipe/start_app 提 AUTO（用户开 Live 即预授权），会话外 CONFIRM
 *
 * E28：所有静默失败路径埋 shizuku_action trace（§6.2 埋点名清单）。
 * 生命周期注：M1.3 才经 AppBindingModule @IntoSet 注册进模块集。
 */
@Singleton
class ShizukuBridge @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val systemBus: SystemBus,
    private val functionRegistry: com.zero.v2.core.moduleapi.ToolRegistrar,
    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    override val metadata = ModuleMetadata(
        id = "system_action",
        displayName = "系统操作",
        description = "Shizuku 特权操作层：系统级手势注入（tap/swipe/input）与系统动作（am/pm/settings）" +
            "——shell-uid 执行，不受目标应用无障碍可见性限制；未激活时手势回退无障碍 dispatchGesture",
        category = ModuleCategory.INSTRUMENT
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config: ModuleConfigBase = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    @Volatile private var remote: ISystemActionService? = null
    @Volatile private var bound = false

    /** Live 语音会话是否进行中（D38 预授权信号源——SystemBus.onVoiceActive，replay=1）。 */
    @Volatile private var liveSessionActive: Boolean = false

    /**
     * 无障碍手势兜底注入点（M1.2）：app 层 AgentRuntimeService.onServiceConnected 注入
     * dispatchGesture 封装（ScreenTextReader.attachRootSupplier 同款模式——app→services 能力注入，
     * 不违 X12）；未注入（无障碍未开/未重开）= 兜底不可用。
     */
    @Volatile private var gestureDispatcher: (suspend (GestureDescription) -> Boolean)? = null

    fun attachGestureDispatcher(dispatcher: (suspend (GestureDescription) -> Boolean)?) {
        gestureDispatcher = dispatcher
        trace(NodeType.SYSTEM_EVENT, "gesture_dispatcher_${if (dispatcher != null) "attached" else "detached"}")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** UserService 绑定参数（tag 稳定标识：跨重启身份一致；version 不匹配触发 Shizuku 重启旧服务）。 */
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, SystemActionUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("system_action")
            .version(1)
    }

    // ═══ Shizuku 生命周期监听（onAwake 注册 / onDestroy 注销）═══

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // Shizuku 服务上线（或本监听注册时 Sticky 即达）→ 绑定/重绑 UserService
        trace(NodeType.SYSTEM_EVENT, "binder_received")
        bindIfReady()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        bound = false
        trace(NodeType.ERROR, "binder_dead: shizuku 服务已停止（重启 Shizuku 后自动重连）")
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            trace(NodeType.SYSTEM_EVENT, "permission_granted")
            bindIfReady()
        } else {
            trace(NodeType.SYSTEM_EVENT, "permission_denied: 重置授权须在 Shizuku 应用内操作")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                remote = ISystemActionService.Stub.asInterface(binder)
                trace(NodeType.SYSTEM_EVENT, "user_service_connected: ${name?.className}")
            } else {
                remote = null
                trace(NodeType.ERROR, "user_service_invalid_binder: ${name?.className}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            trace(NodeType.SYSTEM_EVENT, "user_service_disconnected（进程死亡，等 binder 重收重绑）")
        }
    }

    // ═══ IZeroModule 生命周期 ═══

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        // FC 注册（L3 实测发现的 bug 修复：onAwake 须自调——McpClientModule 先例；
        // 曾漏调致 fc_loop tools=79 无 shell_run，LLM 无从调用）
        
        // D38：Live 会话边沿 → tap/swipe/start_app 重注册（AUTO⇄CONFIRM；register 按名覆写幂等）
        scope.launch {
            systemBus.onVoiceActive.collect { active ->
                if (active != liveSessionActive) {
                    liveSessionActive = active
                    functionRegistry.registerContributions(this@ShizukuBridge)
                    trace(NodeType.SYSTEM_EVENT, "live_session_$active: 动作 FC 权限=${if (active) "AUTO" else "CONFIRM"}")
                }
            }
        }
        // Sticky 监听已在收时即触发 bindIfReady；未激活场景 trace 留痕等待合法窗口
    }

    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }

    override suspend fun onDestroy() {
        _lifecycle.value = ModuleLifecycle.DESTROYED
        scope.cancel()
        FC_NAMES.forEach { functionRegistry.unregister(it) }
        gestureDispatcher = null
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            if (bound) {
                // remove=true → Shizuku server 调 UserService.destroy()（System.exit 结束特权进程）
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
                bound = false
            }
        } catch (e: Exception) {
            trace(NodeType.ERROR, "unbind_failed: ${e.message?.take(80)}")
        }
        remote = null
    }

    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = isShizukuActive()
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    // ═══ Shizuku 激活态与授权 ═══

    /** Shizuku 服务是否在运行（binder 死亡时 Shizuku.getUid() 抛 IllegalStateException）。 */
    fun isShizukuActive(): Boolean = shizukuUid() != null

    /** Shizuku server uid：0=root / 2000=ADB；未激活返回 null。 */
    fun shizukuUid(): Int? = try {
        Shizuku.getUid()
    } catch (e: Exception) {
        null
    }

    /**
     * 请求 Shizuku 授权（弹 Shizuku 授权对话框）。
     * 已授权直接绑定；被拒过（rationale）不再弹——重置须在 Shizuku 应用内。
     */
    fun requestPermission(requestCode: Int = REQUEST_PERMISSION_CODE) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindIfReady()
                return
            }
            if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) {
                trace(NodeType.SYSTEM_EVENT, "permission_blocked: 已拒绝过，请在 Shizuku 应用内重置授权")
                return
            }
            Shizuku.requestPermission(requestCode)
            trace(NodeType.SYSTEM_EVENT, "permission_requested")
        } catch (e: Exception) {
            trace(NodeType.ERROR, "request_permission_failed: ${e.message?.take(80)}")
        }
    }

    /** Shizuku 已激活且已授权时绑定 UserService（幂等；未就绪 trace 留痕静默等待）。 */
    fun bindIfReady() {
        if (bound) return
        try {
            if (!isShizukuActive()) {
                trace(NodeType.SYSTEM_EVENT, "bind_skip_inactive: Shizuku 未运行")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                trace(NodeType.SYSTEM_EVENT, "bind_skip_no_permission: 授权未授予")
                return
            }
            if (Shizuku.getVersion() < 10) {
                trace(NodeType.ERROR, "bind_fail_old_api: version=${Shizuku.getVersion()}（UserService 需 ≥10）")
                return
            }
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            bound = true
            trace(NodeType.SYSTEM_EVENT, "bind_requested")
        } catch (e: Exception) {
            bound = false
            trace(NodeType.ERROR, "bind_failed: ${e.message?.take(80)}")
        }
    }

    // ═══ 桥接 API（M1.2 操作 FC / M1.3 TermuxBridge 消费）═══

    /** UserService 是否已连接就绪。 */
    val isReady: Boolean get() = remote != null

    /**
     * 通用桥接调用：UserService 未连接/超时/异常 → null + trace（E28；调用方降级文案）。
     * binder 调用阻塞 → IO 线程执行。
     */
    suspend fun <T> withService(
        timeoutMs: Long = CALL_TIMEOUT_MS,
        block: (ISystemActionService) -> T
    ): T? = withContext(Dispatchers.IO) {
        val svc = remote ?: run {
            trace(NodeType.ERROR, "call_no_service: UserService 未连接（Shizuku 未激活或未授权）")
            return@withContext null
        }
        try {
            withTimeout(timeoutMs) { block(svc) }
        } catch (e: Exception) {
            trace(NodeType.ERROR, "call_failed: ${e.message?.take(80)}")
            null
        }
    }

    /**
     * 桥接 shell 执行（特权 shell-uid）。
     * @return "exit=N\n输出" 格式文本；未连接/失败 null。
     */
    suspend fun runShell(cmd: String, timeoutMs: Long = CALL_TIMEOUT_MS): String? =
        withService(timeoutMs) { it.runShell(cmd) }

    // ═══ 操作 FC ×9（M1.2 · D63 权限矩阵 + D38 会话预授权）═══

    /**
     * 注册操作 FC。D38：Live 会话内 tap/swipe/start_app 提 AUTO（用户开 Live 即预授权），
     * 会话外 CONFIRM——onVoiceActive 边沿触发本方法重注册（register 按名覆写，幂等）。
     */
    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        val actionPerm = if (liveSessionActive) ActionPermission.AUTO else ActionPermission.CONFIRM

        functionRegistry.register(
            FunctionDefinition(
                name = "tap",
                description = "点击屏幕坐标 (x,y)。Shizuku 激活时系统级注入（不受目标应用无障碍可见性限制）；" +
                    "未激活自动回退无障碍手势。",
                parameters = objSchema("x" to intParam("X 坐标"), "y" to intParam("Y 坐标"), required = listOf("x", "y"))
            ),
            metadata.id, permission = actionPerm
        ) { args ->
            val x = (args["x"] as? Number)?.toInt()
                ?: return@register FunctionResult.failure("x 必填（整数坐标）")
            val y = (args["y"] as? Number)?.toInt()
                ?: return@register FunctionResult.failure("y 必填（整数坐标）")
            handleTap(x, y)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "swipe",
                description = "从 (x1,y1) 滑动到 (x2,y2)。Shizuku 激活时系统级注入；未激活自动回退无障碍手势。",
                parameters = objSchema(
                    "x1" to intParam("起点 X"), "y1" to intParam("起点 Y"),
                    "x2" to intParam("终点 X"), "y2" to intParam("终点 Y"),
                    "duration_ms" to mapOf("type" to "integer", "description" to "持续时间(毫秒)", "default" to 300),
                    required = listOf("x1", "y1", "x2", "y2")
                )
            ),
            metadata.id, permission = actionPerm
        ) { args ->
            val x1 = (args["x1"] as? Number)?.toInt() ?: return@register FunctionResult.failure("x1 必填")
            val y1 = (args["y1"] as? Number)?.toInt() ?: return@register FunctionResult.failure("y1 必填")
            val x2 = (args["x2"] as? Number)?.toInt() ?: return@register FunctionResult.failure("x2 必填")
            val y2 = (args["y2"] as? Number)?.toInt() ?: return@register FunctionResult.failure("y2 必填")
            val duration = ((args["duration_ms"] as? Number)?.toInt() ?: 300).coerceIn(50, 10_000)
            handleSwipe(x1, y1, x2, y2, duration)
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "input_text",
                description = "向当前焦点输入框输入文本（系统级，不经输入法界面）。需 Shizuku。",
                parameters = objSchema("text" to strParam("要输入的文本（空格自动转义）"), required = listOf("text"))
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val text = (args["text"] as? String)?.takeIf { it.isNotEmpty() }
                ?: return@register FunctionResult.failure("text 必填")
            val ok = withService { it.inputText(text) }
            if (ok != null) FunctionResult.success("✅ 已输入 ${text.length} 字符")
            else FunctionResult.failure(shizukuUnavailableText())
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "press_key",
                description = "按下系统按键（如 HOME、BACK、VOLUME_UP、KEYCODE_DPAD_CENTER 等键名）。需 Shizuku。",
                parameters = objSchema("key" to strParam("键名（HOME/BACK/...，自动补 KEYCODE_ 前缀）"), required = listOf("key"))
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val raw = (args["key"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@register FunctionResult.failure("key 必填")
            val keycode = raw.uppercase().let { if (it.startsWith("KEYCODE_")) it else "KEYCODE_$it" }
            if (!Regex("^[A-Z0-9_]+$").matches(keycode)) {
                return@register FunctionResult.failure("非法键名: $raw")
            }
            val ok = withService { it.pressKey(keycode) }
            if (ok != null) FunctionResult.success("✅ 已按键 $keycode")
            else FunctionResult.failure(shizukuUnavailableText())
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "start_app",
                description = "按包名启动应用（打开其主界面）。需 Shizuku。",
                parameters = objSchema("package" to strParam("应用包名，如 com.android.settings"), required = listOf("package"))
            ),
            metadata.id, permission = actionPerm
        ) { args ->
            val pkg = (args["package"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@register FunctionResult.failure("package 必填")
            if (!Regex("^[a-zA-Z0-9._]+$").matches(pkg)) {
                return@register FunctionResult.failure("非法包名: $pkg")
            }
            val out = runShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                ?: return@register FunctionResult.failure(shizukuUnavailableText())
            trace(NodeType.SYSTEM_EVENT, "start_app: $pkg ok=${out.startsWith("exit=0")}")
            if (out.startsWith("exit=0")) FunctionResult.success("✅ 已启动 $pkg")
            else FunctionResult.failure("❌ 启动失败（包名不存在？）: ${out.take(200)}")
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "open_url",
                description = "用系统默认方式打开 URL（网页/深链等）。需 Shizuku。",
                parameters = objSchema("url" to strParam("URL"), required = listOf("url"))
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val url = (args["url"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@register FunctionResult.failure("url 必填")
            val ok = withService { it.startActivity("", "android.intent.action.VIEW", url) }
            trace(NodeType.SYSTEM_EVENT, "open_url: ${url.take(60)} ok=$ok")
            when {
                ok == null -> FunctionResult.failure(shizukuUnavailableText())
                ok == true -> FunctionResult.success("✅ 已打开 $url")
                else -> FunctionResult.failure("❌ 打开失败（无应用可处理该 URL？）")
            }
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "grant_perm",
                description = "授予应用运行时权限（pm grant——程序化授权，免用户手动）。仅限运行时权限类。需 Shizuku。",
                parameters = objSchema(
                    "package" to strParam("目标应用包名"),
                    "permission" to strParam("权限全名，如 android.permission.WRITE_SETTINGS"),
                    required = listOf("package", "permission")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val pkg = (args["package"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@register FunctionResult.failure("package 必填")
            val perm = (args["permission"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@register FunctionResult.failure("permission 必填")
            val ok = withService { it.grantPermission(pkg, perm) }
            trace(NodeType.SYSTEM_EVENT, "grant_perm: $pkg $perm ok=$ok")
            when {
                ok == null -> FunctionResult.failure(shizukuUnavailableText())
                ok == true -> FunctionResult.success("✅ 已授权 $perm → $pkg")
                else -> FunctionResult.failure("❌ 授权失败（非运行时权限或包名错误）")
            }
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "set_setting",
                description = "修改系统设置（settings put）。需 Shizuku。",
                parameters = objSchema(
                    "namespace" to strParam("命名空间：system / global / secure"),
                    "key" to strParam("设置键名"),
                    "value" to strParam("设置值"),
                    required = listOf("namespace", "key", "value")
                )
            ),
            metadata.id, permission = ActionPermission.CONFIRM
        ) { args ->
            val ns = args["namespace"] as? String ?: ""
            if (ns !in setOf("system", "global", "secure")) {
                return@register FunctionResult.failure("namespace 须为 system/global/secure")
            }
            val key = (args["key"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@register FunctionResult.failure("key 必填")
            val value = args["value"] as? String ?: ""
            val ok = withService { it.setSetting(ns, key, value) }
            trace(NodeType.SYSTEM_EVENT, "set_setting: $ns/$key ok=$ok")
            when {
                ok == null -> FunctionResult.failure(shizukuUnavailableText())
                ok == true -> FunctionResult.success("✅ 已设置 $ns/$key = $value")
                else -> FunctionResult.failure("❌ 设置失败（键名错误或只读）")
            }
        }

        functionRegistry.register(
            FunctionDefinition(
                name = "shell_run",
                description = "在 Android 系统 shell 执行命令（Shizuku shell-uid：dumpsys/settings/am/pm 等）。" +
                    "**域边界：这是 Android 设备 shell——未来 linux_exec 才是 Debian 工作站，两者是不同环境勿混用。**" +
                    "高危命令（am force-stop/pm uninstall/pm clear/reboot 等）被拦截，仅限用户手动。",
                parameters = objSchema("command" to strParam("要执行的 shell 命令"), required = listOf("command"))
            ),
            metadata.id, permission = ActionPermission.CONFIRM  // D63：shell_run 恒 CONFIRM
        ) { args ->
            val cmd = (args["command"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@register FunctionResult.failure("command 必填")
            val lowered = cmd.lowercase()
            if (HIGH_RISK_PATTERNS.any { lowered.contains(it) }) {
                trace(NodeType.GUARD_CHECK, "shell_run_blocked: ${cmd.take(60)}")
                return@register FunctionResult.failure("⛔ 高危操作（force-stop/卸载/清数据/重启关机）仅限用户手动执行（D63 FORBIDDEN）")
            }
            val out = runShell(cmd) ?: return@register FunctionResult.failure(shizukuUnavailableText())
            FunctionResult.success(out)
        }
    }

    // ── 手势处理（Shizuku 主径 + 无障碍兜底）──

    private suspend fun handleTap(x: Int, y: Int): FunctionResult {
        val t0 = SystemClock.elapsedRealtime()
        if (withService { it.tap(x, y) } != null) {
            val ms = SystemClock.elapsedRealtime() - t0
            trace(NodeType.SYSTEM_EVENT, "tap: x=$x y=$y via=shizuku latency=${ms}ms")  // B9 观测点（目标 <50ms）
            return FunctionResult.success("✅ 已点击 ($x, $y)（Shizuku ${ms}ms）")
        }
        return gestureFallback("tap", buildTapGesture(x, y), "✅ 已点击 ($x, $y)（无障碍兜底）")
    }

    private suspend fun handleSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): FunctionResult {
        val t0 = SystemClock.elapsedRealtime()
        if (withService { it.swipe(x1, y1, x2, y2, durationMs) } != null) {
            val ms = SystemClock.elapsedRealtime() - t0
            trace(NodeType.SYSTEM_EVENT, "swipe: ($x1,$y1)→($x2,$y2) via=shizuku latency=${ms}ms")
            return FunctionResult.success("✅ 已滑动 ($x1,$y1)→($x2,$y2)（Shizuku ${ms}ms）")
        }
        return gestureFallback("swipe", buildSwipeGesture(x1, y1, x2, y2, durationMs.toLong()),
            "✅ 已滑动 ($x1,$y1)→($x2,$y2)（无障碍兜底）")
    }

    /** Shizuku 未就绪时的无障碍手势兜底；通道也未连接 → 引导文案（B11 降级）。 */
    private suspend fun gestureFallback(label: String, gesture: GestureDescription, successText: String): FunctionResult {
        val disp = gestureDispatcher
            ?: return FunctionResult.failure(shizukuUnavailableText() + "；无障碍手势通道未连接（重开无障碍服务后生效）")
        val ok = try {
            withTimeoutOrNull(GESTURE_FALLBACK_TIMEOUT_MS) { disp(gesture) } == true
        } catch (e: Exception) {
            trace(NodeType.ERROR, "${label}_gesture_failed: ${e.message?.take(60)}")
            false
        }
        trace(NodeType.SYSTEM_EVENT, "$label: via=a11y_fallback ok=$ok")
        return if (ok) FunctionResult.success(successText)
        else FunctionResult.failure("❌ 无障碍手势被取消（目标页面不支持或坐标无效）")
    }

    private fun buildTapGesture(x: Int, y: Int): GestureDescription {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, GESTURE_STROKE_MS))
            .build()
    }

    private fun buildSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
    }

    // ── FC schema 辅助 ──

    private fun objSchema(vararg props: Pair<String, Map<String, Any>>, required: List<String>): Map<String, Any> =
        mapOf("type" to "object", "properties" to props.toMap(), "required" to required)

    private fun strParam(desc: String) = mapOf("type" to "string", "description" to desc)

    private fun intParam(desc: String) = mapOf("type" to "integer", "description" to desc)

    private fun shizukuUnavailableText() =
        "❌ Shizuku 未激活/未授权——系统操作不可用（见 设置→权限中心「系统操作（Shizuku）」）"

    private fun trace(type: NodeType, msg: String) {
        traceManager.record(TRACE_ID, type, metadata.id, msg)
    }

    companion object {
        private const val TRACE_ID = "shizuku_action"
        private const val REQUEST_PERMISSION_CODE = 1001
        private const val CALL_TIMEOUT_MS = 15_000L
        private const val GESTURE_FALLBACK_TIMEOUT_MS = 3_000L
        private const val GESTURE_STROKE_MS = 50L

        /** 本模块注册的操作 FC 清单（onDestroy 注销用） */
        private val FC_NAMES = listOf(
            "tap", "swipe", "input_text", "press_key", "start_app",
            "open_url", "grant_perm", "set_setting", "shell_run"
        )

        /** D63 FORBIDDEN：高危命令模式（仅限用户手动，FC 路径硬拦截） */
        private val HIGH_RISK_PATTERNS = listOf(
            "am force-stop", "pm uninstall", "pm clear", "pm reset-permissions", "reboot", "shutdown"
        )
    }
}
