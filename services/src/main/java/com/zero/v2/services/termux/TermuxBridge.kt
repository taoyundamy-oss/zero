package com.zero.v2.services.termux

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.AgentTraceEvent
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.shizuku.ShizukuBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Termux 工作站桥 —— M1.4（方案 §1.2/§15.4 · D69 工作站装载）。
 *
 * 职责：
 *   - RUN_COMMAND 通道：sendCommand（经 com.termux.app.**RunCommandService**——官方 wiki 实测勘误，
 *     计划草图的 TermuxService 已过时）+ PendingIntent 结果回传（stdout/stderr/exitCode）
 *   - **ensureTermuxAlive**（B-II-10 鸡蛋-蛋前置：RUN_COMMAND 需 Termux 进程——Shizuku am start 拉起）
 *   - **bootstrap**（D69 三段：Termux pkg python/proot-distro → proot-distro install debian →
 *     Debian apt 基础包 + /root/work//root/zero 目录契约 + shim 双侧部署）
 *   - 存在性检测 / 启动引导文案 / healthCheck（python3 --version 探针）
 *   - termux-wake-lock 保活（§12.6.1 杠杆——首模块 enable 时 acquire，全禁用 release）
 *
 * 前置（L3 用户操作，guideText 引导）：
 *   ① 应用信息→权限→其他权限→"Run in Termux"（com.termux.permission.RUN_COMMAND，危险级自定义权限）
 *   ② Termux 内 ~/.termux/termux.properties 设 allow-external-apps=true 后重启 Termux（安全闸）
 *
 * E28：所有静默失败路径埋 termux_command trace（§6.2 埋点名清单）。
 * M2.0 扩 ensureSupervisor；M3.0 扩工作站 FC——本步无 FC。
 */
@Singleton
class TermuxBridge @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val shizukuBridge: ShizukuBridge,  // M1.4：ensureTermuxAlive 拉起 + pidof 判活
    private val traceManager: AgentTraceManager,

) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    override val metadata = ModuleMetadata(
        id = "termux",
        displayName = "Termux 工作站",
        description = "Termux/Linux 工作站基建：RUN_COMMAND 命令执行、结果回传、健康检查与 wake-lock 保活" +
            "（D69 Linux 工作站载体）",
        category = ModuleCategory.INSTRUMENT
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = TermuxBridgeConfig()  // §9.1 参数决策表（TermuxBridge 四字段）

    /** 结果回调路由表（token → 回调）；PendingIntent FLAG_ONE_SHOT + 唯一 requestCode 每次独立 */
    private val pendingResults = ConcurrentHashMap<String, (String, Int) -> Unit>()
    private var resultReceiver: BroadcastReceiver? = null
    private val requestIdCounter = AtomicInteger(1000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bootstrapMutex = Mutex()
    private val supervisorMutex = Mutex()
    private val pairingMutex = Mutex()
    @Volatile private var controlToken: String? = null

    fun authorizeSupervisorRequest(request: Request): Request =
        SupervisorAuthorization.authorize(request, supervisorPort, controlToken)

    private suspend fun pairSupervisor(): Boolean = pairingMutex.withLock {
        if (controlToken != null) return@withLock true
        val result = awaitCommand(PYTHON3, listOf("-c", SupervisorAuthorization.script(TERMUX_PREFIX)),
            timeoutMs = 15_000L)
        controlToken = result?.takeIf { it.second == 0 }?.first?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        controlToken != null
    }

    // ═══ 生命周期 ═══

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE

        val cfg = config as TermuxBridgeConfig
        trace(NodeType.SYSTEM_EVENT, "awake: installed=${isTermuxInstalled()} autoBootstrap=${cfg.autoBootstrapOnStart}")
        if (isTermuxInstalled() && cfg.autoBootstrapOnStart) {
            scope.launch { bootstrap() }
        }
        // M2.0：已引导 → supervisor 常驻（§12.6 单 supervisor 统管）+ 低频守护（死亡→重启序列）
        scope.launch {
            if (isTermuxInstalled() && isBootstrapped()) ensureSupervisor()
            supervisorWatchdog()
        }
    }

    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }

    override suspend fun onDestroy() {
        _lifecycle.value = ModuleLifecycle.DESTROYED
        scope.cancel()
        resultReceiver?.let {
            try { appContext.unregisterReceiver(it) } catch (_: Exception) {}
        }
        resultReceiver = null
        pendingResults.clear()
    }

    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {
        // 管理器已把新值 fromJson 进 config 实例（AssistantModule 同款语义）——读当前值即可；
        // autoBootstrapOnStart 关→开边沿立即触发引导（模块详情页开关即 bootstrap 入口，免重启）
        val cfg = config as TermuxBridgeConfig
        trace(NodeType.SYSTEM_EVENT, "config_changed: autoBootstrap=${cfg.autoBootstrapOnStart} port=${cfg.supervisorPort}")
        supPost("/release/emergency", JSONObject().put("enabled", cfg.emergencyStop))
        if (cfg.autoBootstrapOnStart && isTermuxInstalled()) {
            scope.launch { bootstrap() }  // bootstrap 自身幂等（marker + Mutex），重复触发安全
        }
    }

    /**
     * M3.0 工作站 FC（D69 §15 三通道 Zero 侧暴露——Agent 直接获得"一台电脑"，全 AUTO D67/D69）。
     * FC description 内嵌工作站使用规范（P1-1 域边界 / P1-3 目录契约 / 长任务 nohup+/service /
     * 64KB 截断 / 成熟工具走 develop_module 的时机建议）。
     */
    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition(
                name = "linux_exec",
                description = "在 Debian Linux 工作站（Termux proot 容器）内执行 shell 命令——探索式开发核心原语" +
                    "（ls/grep/试跑/看报错循环）。⚠️ 域边界：这是 Linux 工作站用户态 ≠ shell_run（Android 系统层，" +
                    "另一工具）——两者勿混用。工作区规范：在 /root/work 下工作；勿改动 /root/zero（Zero 运行时，" +
                    "防自毁）。默认超时 120 秒（timeout_s 可调，上限见模块配置）；分钟级以上长任务应 nohup 放后台" +
                    "或用 service 常驻，勿同步等待；输出超 64KB 自动截尾。session_id 隔离会话 cwd（同 session " +
                    "粘性沿用上次目录）。反复调用的成熟命令建议沉淀为动态模块（develop_module）",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "cmd" to mapOf("type" to "string", "description" to "shell 命令（bash -c 执行）"),
                        "cwd" to mapOf("type" to "string", "description" to "工作目录（绝对路径；缺省沿用 session 上次）"),
                        "timeout_s" to mapOf("type" to "integer", "description" to "超时秒数（默认 120）"),
                        "session_id" to mapOf("type" to "string", "description" to "会话隔离标识（默认 default）")
                    ),
                    "required" to listOf("cmd")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> linuxExec(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "linux_ls",
                description = "列出 Linux 工作站目录内容（Debian 文件系统——/root/work 工作区等）。" +
                    "返回 name/size/mtime/是否目录",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "绝对路径（默认 /root/work）")
                    ),
                    "required" to listOf("path")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> linuxLs(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "linux_read_file",
                description = "读 Linux 工作站文本文件（每次最多 64KB；大文件用 offset 分段，" +
                    "二进制用 linux_download）",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "绝对路径"),
                        "offset" to mapOf("type" to "integer", "description" to "起始字节偏移（默认 0）")
                    ),
                    "required" to listOf("path")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> linuxReadFile(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "linux_write_file",
                description = "写小文本文件到 Linux 工作站（覆盖写）。大文件/二进制走 linux_upload。" +
                    "写代码/配置/脚本用",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "目标绝对路径（目录自动创建）"),
                        "content" to mapOf("type" to "string", "description" to "全文内容（UTF-8）")
                    ),
                    "required" to listOf("path", "content")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> linuxWriteFile(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "linux_upload",
                description = "把工作目录（SAF）文件上传到 Linux 工作站——剪辑/转码/分析前送原料。" +
                    "from_saf 为 Zero 工作目录内文件引用（如 downloads/x.mp3）",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "from_saf" to mapOf("type" to "string", "description" to "Zero 工作目录内文件引用"),
                        "to_linux" to mapOf("type" to "string", "description" to "工作站目标绝对路径（如 /root/work/in.mp3）")
                    ),
                    "required" to listOf("from_saf", "to_linux")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> linuxUpload(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "linux_download",
                description = "把 Linux 工作站文件下载到 Zero 工作目录（linux/ 子目录）——产物交付" +
                    "（转码音频/生成的文件给用户）",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "from_linux" to mapOf("type" to "string", "description" to "工作站源绝对路径"),
                        "to_saf" to mapOf("type" to "string", "description" to "目标文件名（默认取源文件名）")
                    ),
                    "required" to listOf("from_linux")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> linuxDownload(args) }
    }
    override suspend fun checkAvailability(): Boolean = isTermuxInstalled()
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    // ═══ 存在性与引导 ═══

    fun isTermuxInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(TERMUX_PACKAGE, 0) != null
    } catch (_: Exception) { false }

    /** 启动引导文案（FC 降级路径与权限中心共用，B11）。 */
    fun guideText(): String = if (isTermuxInstalled()) {
        "Termux 已安装但命令通道未通——检查：① 本应用 信息→权限→其他权限→「Run in Termux」需授予；" +
            "② Termux 内 ~/.termux/termux.properties 设 allow-external-apps=true 后重启 Termux"
    } else {
        "未安装 Termux——模块化工作站基建不可用（对话/记忆/既有功能不受影响）。请从 F-Droid/GitHub 安装 Termux"
    }

    // ═══ RUN_COMMAND 命令通道 ═══

    /**
     * 经 RUN_COMMAND Intent 执行 Termux 命令（异步）。
     * @param path 可执行文件绝对路径（$PREFIX/bin/...）
     * @param onResult 非空时经 PendingIntent 回传 (stdout+stderr 合并文本, exitCode)
     * @return startService 是否受理；false = Termux 未运行/后台启动受限/未授权（guideText 引导）
     */
    suspend fun sendCommand(
        path: String,
        args: List<String>,
        workdir: String? = null,
        background: Boolean = true,
        onResult: ((String, Int) -> Unit)? = null
    ): Boolean = sendCommandInternal(path, args, workdir, background, onResult)

    /** 非挂起实现（awaitCommand 在 suspendCancellableCoroutine 非挂起块内复用）。 */
    private fun sendCommandInternal(
        path: String,
        args: List<String>,
        workdir: String? = null,
        background: Boolean = true,
        onResult: ((String, Int) -> Unit)? = null
    ): Boolean {
        if (onResult != null) ensureResultReceiver()
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, path)
            putExtra(EXTRA_ARGUMENTS, args.toTypedArray())
            workdir?.let { putExtra(EXTRA_WORKDIR, it) }
            putExtra(EXTRA_BACKGROUND, background)
            if (onResult != null) {
                val token = UUID.randomUUID().toString().substring(0, 8)
                pendingResults[token] = onResult
                putExtra(EXTRA_PENDING_INTENT, buildResultPendingIntent(token))
            }
        }
        return try {
            val started = appContext.startService(intent) != null
            trace(NodeType.SYSTEM_EVENT,
                "run_command: path=${path.substringAfterLast('/')} args=${args.size} bg=$background cb=${onResult != null} started=$started")
            started
        } catch (e: Exception) {
            // B11 降级：Termux 未运行（鸡蛋-蛋）/ 后台 startService 受限——M1.4 ensureTermuxAlive 处置
            trace(NodeType.ERROR, "run_command_failed: ${e.message?.take(80)}")
            false
        }
    }

    /**
     * 同步等待版：挂起直到结果回传或超时。
     * @return (输出文本, exitCode)；null = 发送失败或超时
     */
    suspend fun awaitCommand(
        path: String,
        args: List<String>,
        workdir: String? = null,
        background: Boolean = true,
        timeoutMs: Long = 30_000L
    ): Pair<String, Int>? = try {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val sent = sendCommandInternal(path, args, workdir, background) { stdout, exit ->
                    if (cont.isActive) cont.resume(stdout to exit)
                }
                if (!sent && cont.isActive) cont.resume(null)
            }
        }
    } catch (e: Exception) {
        trace(NodeType.ERROR, "await_command_failed: ${e.message?.take(60)}")
        null
    }

    // ═══ 健康检查与保活 ═══

    /** 健康检查：Termux python 可执行（bootstrap 完成度探针）。@return "Python 3.x" 或 null。 */
    suspend fun healthCheck(): String? {
        if (!isTermuxInstalled()) return null
        val res = awaitCommand(PYTHON3, listOf("--version"), timeoutMs = 15_000L) ?: return null
        return res.first.lineSequence().firstOrNull { it.contains("Python", ignoreCase = true) }
            .also { trace(NodeType.SYSTEM_EVENT, "health_check: ${it ?: "无版本回显"}") }
    }

    /** termux-wake-lock（§12.6.1 主保活杠杆——首模块 enable 时；wakelock 纪律：全禁用时 release）。 */
    suspend fun acquireWakeLock(): Boolean =
        sendCommand("$TERMUX_BIN/termux-wake-lock", emptyList())

    suspend fun releaseWakeLock(): Boolean =
        sendCommand("$TERMUX_BIN/termux-wake-unlock", emptyList())

    // ═══ M1.4：存活判定 + Debian 工作站引导（D69）═══

    /**
     * Termux 进程是否在跑（Shizuku pidof 精确判定；Shizuku 不可达时乐观返回 true——
     * RUN_COMMAND 冷启动多数场景可自举，失败由调用方按 B11 降级）。
     */
    private suspend fun isTermuxRunning(): Boolean {
        val out = shizukuBridge.runShell("pidof com.termux") ?: return true
        return out.startsWith("exit=0") && out.substringAfter("exit=0").trim().isNotEmpty()
    }

    /**
     * 鸡蛋-蛋前置（B-II-10）：RUN_COMMAND 需 Termux 进程在跑。
     * 拉起主径=Shizuku am start（shell-uid 无 BAL 限制 + 隐蔽 flag 防闪屏）；
     * Shizuku 不可达兜底=app 侧直启 launcher（前台合法；后台 BAL 受限 catch 留痕）。
     */
    suspend fun ensureTermuxAlive(): Boolean {
        if (isTermuxRunning()) return true
        trace(NodeType.SYSTEM_EVENT, "termux_not_running: 拉起（Shizuku am start 隐蔽 flag）")
        val viaShizuku = shizukuBridge.runShell(
            "am start -n com.termux/.app.TermuxActivity --activity-no-history --activity-no-anim")
        if (viaShizuku == null) {
            try {
                appContext.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(TERMUX_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                trace(NodeType.SYSTEM_EVENT, "termux_start: app 侧直启（Shizuku 不可达兜底）")
            } catch (e: Exception) {
                trace(NodeType.ERROR, "termux_start_failed: ${e.message?.take(60)}")
            }
        }
        delay(1500)
        val wl = acquireWakeLock()
        val running = isTermuxRunning()
        trace(NodeType.SYSTEM_EVENT, "ensure_termux: shizuku=${viaShizuku != null} wakelock=$wl running=$running")
        return running
    }

    /**
     * 首跑幂等引导（D69 工作站装载，三段 + shim 双侧部署）：
     * ① Termux pkg python/proot-distro + $PREFIX/zero/zero_sdk
     * ② proot-distro install debian（已装幂等）
     * ③ Debian apt 基础包（python3/pip/git/build-essential/curl/ffmpeg）
     *    + 目录契约 /root/work（工作区）/root/zero/modules（§15.4 P1-3）
     *    + shim 写入 Debian rootfs /root/zero/zero_sdk（模块在 Debian 内 import——不依赖 proot bind）
     * 标记 $PREFIX/zero/.bootstrap_done；Mutex + marker 双幂等；耗时可达 20min（一次性）。
     */
    suspend fun bootstrap(): Boolean {
        if (!bootstrapMutex.tryLock()) {
            trace(NodeType.SYSTEM_EVENT, "bootstrap_busy: 引导已在进行，忽略重复触发")
            return false
        }
        try {
            if (!isTermuxInstalled()) {
                trace(NodeType.ERROR, "bootstrap_failed: Termux 未安装")
                return false
            }
            if (!ensureTermuxAlive()) {
                trace(NodeType.ERROR, "bootstrap_failed: Termux 拉起失败（见 ensure_termux trace）")
                return false
            }
            val marker = awaitCommand(BASH, listOf("-c", "test -f $TERMUX_PREFIX/zero/.bootstrap_done"), timeoutMs = 15_000L)
            if (marker?.second == 0) {
                trace(NodeType.SYSTEM_EVENT, "bootstrap_skipped: 已引导过（幂等标记在）")
                return true
            }

            trace(NodeType.SYSTEM_EVENT, "bootstrap_start: 装 Termux pkg + Debian 工作站（D69）——下载量大，预计数分钟")
            if (!deployAsset(ZERO_TOOL)) return false
            if (!deployAsset(ZERO_SUPERVISOR)) return false  // M2.0：supervisor 与 shim 同批部署
            if (!deployAsset(ZERO_RELEASE)) return false
            if (!deployAsset(ZERO_HOST)) return false

            // L3 实测加固（2026-09-16）：
            // ① </dev/null 封 stdin——后台 RUN_COMMAND 无 tty，pkg/apt 交互提示（镜像选择/确认）
            //    会永久挂死（模拟器实证 do_select 卡 15min，真机同构风险）
            // ② sources.list 自愈——Termux 无镜像配置时 pkg 走交互式测镜像（同挂死路径）；
            //    预写官方默认源（幂等：已有 termux 源不动）
            // ③ DEBIAN_FRONTEND=noninteractive——Debian apt 免交互（tzdata 等配置提示）
            // ④ rootfs 布局探测——proot-distro 5.x 为 containers/debian/rootfs（模拟器实测），
            //    旧版为 install-rootfs/debian/rootfs（计划草图时代）；mkdir -p 会凭空造幽灵目录
            //    造成"假成功"，故先探测取存在者再建目录契约
            val rootfsDetect = "R=$TERMUX_PREFIX/var/lib/proot-distro/containers/debian/rootfs; " +
                "[ -d \"\$R\" ] || R=$TERMUX_PREFIX/var/lib/proot-distro/install-rootfs/debian/rootfs"
            val t0 = "mkdir -p $TERMUX_PREFIX/etc/apt/sources.list.d && " +
                "grep -q termux $TERMUX_PREFIX/etc/apt/sources.list 2>/dev/null || " +
                "echo 'deb https://packages-cf.termux.dev/apt/termux-main/ stable main' > $TERMUX_PREFIX/etc/apt/sources.list"
            val t1 = "pkg install -y python proot-distro && mkdir -p $TERMUX_PREFIX/zero/zero_sdk"
            val t2 = "proot-distro install debian || true"  // 已装幂等
            val t3 = "proot-distro login debian -- bash -c 'export DEBIAN_FRONTEND=noninteractive && " +
                "apt update && apt install -y python3 python3-pip python3-venv git build-essential curl ffmpeg'"
            val t4 = "$rootfsDetect && mkdir -p \$R/root/work \$R/root/zero/modules \$R/root/zero/zero_sdk " +
                "&& cp $TERMUX_PREFIX/zero/zero_sdk/$ZERO_TOOL \$R/root/zero/zero_sdk/"
            val res = awaitCommand(BASH, listOf("-c", "$t0 && $t1 && $t2 && $t3 && $t4 </dev/null"),
                background = true, timeoutMs = BOOTSTRAP_TIMEOUT_MS)
            return if (res != null && res.second == 0) {
                awaitCommand(BASH, listOf("-c", "touch $TERMUX_PREFIX/zero/.bootstrap_done"), timeoutMs = 15_000L)
                trace(NodeType.SYSTEM_EVENT, "bootstrap_done: Debian 工作站就绪 ${res.first.take(120)}")
                true
            } else {
                trace(NodeType.ERROR, "bootstrap_failed: exit=${res?.second ?: "timeout/未回传"} ${res?.first?.take(200)}")
                false
            }
        } finally {
            bootstrapMutex.unlock()
        }
    }

    /**
     * 部署 assets/zero_sdk/ 下文件到 $PREFIX/zero/zero_sdk/（base64 经 bash 落盘——
     * Termux 读不到 app 私有存储，SAF/直拷不可行）。M2.0 supervisor 同法复用。
     */
    private suspend fun deployAsset(assetName: String, targetDir: String = "$TERMUX_PREFIX/zero/zero_sdk"): Boolean {
        val content = try {
            appContext.assets.open("zero_sdk/$assetName").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            trace(NodeType.ERROR, "asset_read_failed: $assetName ${e.message?.take(60)}")
            return false
        }
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val script = "mkdir -p $targetDir && echo '$b64' | base64 -d > $targetDir/$assetName.tmp && " +
            "mv $targetDir/$assetName.tmp $targetDir/$assetName"
        val res = awaitCommand(BASH, listOf("-c", script), timeoutMs = 30_000L)
        val ok = res?.second == 0
        if (!ok) trace(NodeType.ERROR, "deploy_failed: $assetName exit=${res?.second} ${res?.first?.take(120)}")
        else trace(NodeType.SYSTEM_EVENT, "deploy_ok: $assetName (${content.length} 字符)")
        return ok
    }

    // ═══ M2.0：supervisor 控制面（§15/§十六——双通道/固定端口/重启序列）═══

    /** supervisor 控制面端口（M2.2 McpClientModule 消费——配置域归本模块） */
    val supervisorPort: Int get() = (config as TermuxBridgeConfig).supervisorPort

    // ═══ M3.1：supervisor 面公开 API（CompileManager 消费）═══
    // §十六：TermuxBridge 是与 supervisor 对话的单一权威——sup 路由的公开消费统一走此处，
    // 避免第二套 HTTP 面分叉（McpClientModule 的 supPost 属 M2.2 既有实现，不动）。

    /** manifest deps 安装超时（§9.1——M3.1 CompileManager 消费，P1-7 与探活分离） */
    val installTimeoutS: Int get() = (config as TermuxBridgeConfig).installTimeoutS

    /** 跨会话崩数阈值 K（§9.1 supervisor 崩溃治理——M4.1 McpClientModule 守卫消费） */
    val crashAutoDisableK: Int get() = (config as TermuxBridgeConfig).crashAutoDisableK

    /** E3：紧急总开关（独立于 LLM/模块/网络——仅用户可解除） */
    val isEmergencyStop: Boolean get() = (config as TermuxBridgeConfig).emergencyStop

    /** supervisor GET（失败 null——E28 由调用方埋点；相对路径如 /health） */
    suspend fun supGet(path: String, timeoutMs: Long = 15_000L): JSONObject? = withContext(Dispatchers.IO) {
        supRequest(Request.Builder().url(supUrl(path)).build(), timeoutMs)?.use { r ->
            if (!r.isSuccessful) null
            else try { JSONObject(r.body?.string() ?: "null") } catch (_: Exception) { null }
        }
    }

    /** Bounded transfer through the existing supervisor transport; never exposes arbitrary remote URLs. */
    suspend fun readMediaFile(path: String): ByteArray? = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && '\u0000' !in path) { "需要工作站绝对路径" }
        val request = Request.Builder().url(supUrl("/file?path=${urlEnc(path)}")).build()
        supRequest(request, MEDIA_READ_TIMEOUT_MS)?.use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.byteStream()?.use(com.zero.v2.core.common.ContentLimits::readBounded)
        }
    }

    /** supervisor POST JSON（失败 null）；timeoutMs 默认 20s（/module start 探活窗上限时调用方放宽） */
    suspend fun supPost(path: String, body: JSONObject, timeoutMs: Long = 20_000L): JSONObject? =
        withContext(Dispatchers.IO) {
            supRequest(Request.Builder().url(supUrl(path))
                .post(body.toString().toRequestBody(JSON_BODY_MEDIA)).build(), timeoutMs)?.use { r ->
                if (!r.isSuccessful) null
                else try { JSONObject(r.body?.string() ?: "null") } catch (_: Exception) { null }
            }
        }

    /** supervisor POST RAW body（/file/upload 通道——M3.1 写模块源码用） */
    suspend fun supPostRaw(path: String, bytes: ByteArray, timeoutMs: Long = 30_000L): JSONObject? =
        withContext(Dispatchers.IO) {
            supRequest(Request.Builder().url(supUrl(path))
                .post(bytes.toRequestBody(OCTET_MEDIA)).build(), timeoutMs)?.use { r ->
                if (!r.isSuccessful) null
                else try { JSONObject(r.body?.string() ?: "null") } catch (_: Exception) { null }
            }
        }

    /** supervisor /health 快照（P0-4：modules[].endpoint=runtime 回报值，DB 仅 lastEndpoint） */
    data class SupervisorHealth(val pid: Int, val uptimeS: Long, val rootfs: Boolean, val raw: JSONObject)

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private suspend fun isBootstrapped(): Boolean =
        awaitCommand(BASH, listOf("-c", "test -f $TERMUX_PREFIX/zero/.bootstrap_done"), timeoutMs = 10_000L)?.second == 0

    /**
     * supervisor 健康探测——§十六双通道：HTTP loopback（主）→ AF_UNIX 抽象名（兜底）→ null（DEAD）。
     */
    suspend fun health(): SupervisorHealth? = withContext(Dispatchers.IO) {
        if (!pairSupervisor()) return@withContext null
        val port = (config as TermuxBridgeConfig).supervisorPort
        val httpBody: String? = try {
            httpClient.newCall(authorizeSupervisorRequest(
                Request.Builder().url("http://127.0.0.1:$port/health").build()))
                .execute().use { r ->
                    if (r.code == java.net.HttpURLConnection.HTTP_UNAUTHORIZED) controlToken = null
                    if (r.isSuccessful) r.body?.string() else null
                }
        } catch (_: Exception) { null }
        if (controlToken == null && !pairSupervisor()) return@withContext null
        val body = httpBody ?: healthViaSocket() ?: return@withContext null
        try {
            val o = JSONObject(body)
            if (!o.optBoolean("alive")) return@withContext null
            SupervisorHealth(o.optInt("pid"), o.optLong("uptime_s"), o.optBoolean("rootfs"), o)
        } catch (_: Exception) { null }
    }

    /** 通道二：AF_UNIX 抽象名 \0zero-supervisor（4B 大端帧，与 zero_supervisor.socket_server 对称）。 */
    private fun healthViaSocket(): String? = try {
        android.net.LocalSocket().use {
            it.connect(android.net.LocalSocketAddress(
                "zero-supervisor", android.net.LocalSocketAddress.Namespace.ABSTRACT))
            it.soTimeout = SupervisorFrame.READ_TIMEOUT_MS
            val expectedUid = appContext.packageManager.getApplicationInfo(TERMUX_PACKAGE, 0).uid
            check(it.peerCredentials.uid == expectedUid) { "Unexpected supervisor peer" }
            val req = JSONObject().put("method", "GET").put("path", "/health")
                .put("token", controlToken).toString().toByteArray()
            val out = it.outputStream
            out.write(byteArrayOf(
                (req.size ushr 24).toByte(), (req.size ushr 16).toByte(),
                (req.size ushr 8).toByte(), req.size.toByte()))
            out.write(req)
            out.flush()
            val resp = JSONObject(String(SupervisorFrame.read(it.inputStream), Charsets.UTF_8))
            if (resp.optInt("status") == 200) resp.optJSONObject("body")?.toString() else null
        }
    } catch (_: Exception) { null }

    /** 起 supervisor（幂等）：alive 探测→部署缺失脚本→RUN_COMMAND 起（argv 传端口）→复探。 */
    suspend fun ensureSupervisor(): Boolean = supervisorMutex.withLock { ensureSupervisorLocked() }

    // Each failed upgrade prerequisite must stop before replacing the running supervisor.
    @Suppress("ReturnCount")
    private suspend fun ensureSupervisorLocked(): Boolean {
        val owner = observeSupervisorOwner()
        if (owner.timedOut) return false
        val current = owner.health
        val hashes = supervisorBundleHashes() ?: return false
        if (SupervisorBundle.matches(current?.raw, hashes)) {
            return supPost("/release/emergency", JSONObject().put("enabled", isEmergencyStop))?.optBoolean("ok") == true
        }
        if (!ensureTermuxAlive()) {
            trace(NodeType.ERROR, "supervisor_launch_failed: Termux 不可达")
            return false
        }
        // Install venv support once when upgrading the host protocol; never alter global pip packages.
        val upgrade = "proot-distro login debian -- bash -c '" +
            "test -f /root/zero/.release_v1 || { apt-get update && " +
            "DEBIAN_FRONTEND=noninteractive apt-get install -y python3-venv && " +
            "mkdir -p /root/zero && touch /root/zero/.release_v1; }'"
        val ready = awaitCommand(BASH, listOf("-c", upgrade), timeoutMs = BOOTSTRAP_TIMEOUT_MS)
        if (ready?.second != 0) return false
        if (!stageSupervisorBundle(hashes) || !hostBundleAction("prepare")) return false
        if (current != null && current.pid > 1) {
            if (!stopSupervisor(current.pid)) return false
        }
        return startUpdatedSupervisor(hashes, current)
    }

    private suspend fun startUpdatedSupervisor(hashes: JSONObject, current: SupervisorHealth?): Boolean {
        if (!hostBundleAction("install")) return restoreSupervisorBundle(current)
        val alive = launchSupervisor()
        return if (!SupervisorBundle.matches(alive?.raw, hashes) || !hostBundleAction("accept")) {
            // A failed/slow recovery still owns the listening port even when readiness timed out.
            val failedOwner = alive ?: health()
            val stopped = if (failedOwner != null) stopSupervisor(failedOwner.pid) else !hasSupervisorProcess()
            if (stopped) restoreSupervisorBundle(current) else false
        } else {
            supPost("/release/emergency", JSONObject().put("enabled", isEmergencyStop))?.optBoolean("ok") == true
        }
    }

    private suspend fun supervisorBundleHashes(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            SupervisorBundle.hashes(SupervisorBundle.files.associateWith { name ->
                appContext.assets.open("zero_sdk/$name").use { it.readBytes() }
            })
        } catch (_: Exception) { null }
    }

    private suspend fun stageSupervisorBundle(hashes: JSONObject): Boolean {
        val stage = "$TERMUX_PREFIX/zero/.host-next"
        for (name in SupervisorBundle.files) if (!deployAsset(name, stage)) return false
        val encoded = Base64.getEncoder().encodeToString(hashes.toString().toByteArray(Charsets.UTF_8))
        val script = "echo '$encoded' | base64 -d > $stage/bundle.json.tmp && " +
            "mv $stage/bundle.json.tmp $stage/bundle.json"
        return awaitCommand(BASH, listOf("-c", script), timeoutMs = 30_000L)?.second == 0
    }

    private suspend fun hostBundleAction(action: String): Boolean {
        val stage = "$TERMUX_PREFIX/zero/.host-next"
        val script = "R=$TERMUX_PREFIX/var/lib/proot-distro/containers/debian/rootfs; " +
            "[ -d \"\$R\" ] || R=$TERMUX_PREFIX/var/lib/proot-distro/install-rootfs/debian/rootfs; " +
            "test -d \"\$R\" && $PYTHON3 $stage/$ZERO_HOST $action $stage " +
            "$TERMUX_PREFIX/zero/zero_sdk \"\$R/root/zero/zero_sdk\""
        val result = awaitCommand(BASH, listOf("-c", script), timeoutMs = 30_000L)
        if (result?.second != 0) trace(NodeType.ERROR, "host_bundle_$action failed")
        return result?.second == 0
    }

    private suspend fun stopSupervisor(pid: Int): Boolean {
        if (pid <= 1) return false
        val sdk = "$TERMUX_PREFIX/zero/zero_sdk"
        // The old live helper may not implement stop-owner; the staged bundle was validated before stopping.
        val helper = "$TERMUX_PREFIX/zero/.host-next/$ZERO_HOST"
        return awaitCommand(PYTHON3, listOf(helper, "stop-owner", pid.toString(),
            "$sdk/$ZERO_SUPERVISOR"), timeoutMs = 35_000L)?.second == 0
    }

    private suspend fun restoreSupervisorBundle(previous: SupervisorHealth?): Boolean {
        if (!hostBundleAction("restore")) return false
        if (previous != null) launchSupervisor()
        trace(NodeType.ERROR, "host_bundle_upgrade_failed: previous scripts restored; upgrade not accepted")
        return false
    }

    private suspend fun launchSupervisor(): SupervisorHealth? {
        val port = (config as TermuxBridgeConfig).supervisorPort
        val sent = sendCommand(PYTHON3, listOf("$TERMUX_PREFIX/zero/zero_sdk/$ZERO_SUPERVISOR", port.toString()),
            background = true)
        trace(NodeType.SYSTEM_EVENT, "supervisor_launch: port=$port sent=$sent")
        val alive = if (sent) awaitSupervisorHealth() else null
        trace(if (alive != null) NodeType.SYSTEM_EVENT else NodeType.ERROR,
            "supervisor_health: alive=${alive != null} pid=${alive?.pid} rootfs=${alive?.rootfs}")
        return alive
    }

    private suspend fun awaitSupervisorHealth(): SupervisorHealth? =
        withTimeoutOrNull(SUPERVISOR_START_TIMEOUT_MS) {
            var observation: SupervisorHealth? = null
            while (observation == null) {
                delay(SUPERVISOR_HEALTH_POLL_MS)
                observation = health()?.takeIf { SupervisorBundle.isReady(it.raw) }
            }
            observation
        }

    private data class SupervisorOwnerObservation(val health: SupervisorHealth?, val timedOut: Boolean)

    private suspend fun observeSupervisorOwner(): SupervisorOwnerObservation {
        val current = health()
        val waiting = if (current != null) !SupervisorBundle.isReady(current.raw) else hasSupervisorProcess()
        return if (waiting) {
            trace(NodeType.SYSTEM_EVENT, "supervisor_starting: wait for the existing owner before updating assets")
            val ready = awaitSupervisorHealth()
            SupervisorOwnerObservation(ready, ready == null)
        } else SupervisorOwnerObservation(current, false)
    }

    private suspend fun hasSupervisorProcess(): Boolean {
        val script = """
            import os
            from pathlib import Path
            found = False
            for path in Path('/proc').iterdir():
                if not path.name.isdigit():
                    continue
                try:
                    args = (path/'cmdline').read_bytes().split(b'\0')
                    if path.stat().st_uid == os.getuid() and len(args)>1 and args[1] == b'$TERMUX_PREFIX/zero/zero_sdk/$ZERO_SUPERVISOR':
                        found = True
                except OSError:
                    pass
            print('1' if found else '0')
        """.trimIndent()
        return awaitCommand(PYTHON3, listOf("-c", script), timeoutMs = 15_000L)?.let {
            it.second == 0 && it.first.trim() == "1"
        } == true
    }

    /**
     * 低频守护（§12.6.3 重启序列本侧实现）：60s 探活 → 死亡→ensureSupervisor；
     * 上限 3 次/10min（指数语义简化为窗口计数——超限停手等窗口滑出，全局 K 治理在 M2.2）。
     * 偏差注：计划写"挂 AgentRuntime tick（D18）"——TermuxBridge 与 AgentRuntime 的 tick 缝在
     * M2.2 挂载时统一接线，本步自治循环先行（同语义低频只探活+有界重启）。
     */
    private suspend fun supervisorWatchdog() {
        val restarts = ArrayDeque<Long>()
        while (true) {
            delay(60_000L)
            if (!isTermuxInstalled()) continue
            if (health() != null) continue
            val now = System.currentTimeMillis()
            while (restarts.isNotEmpty() && now - restarts.first() > 600_000L) restarts.removeFirst()
            if (restarts.size >= 3) continue
            trace(NodeType.SYSTEM_EVENT, "termux_restart: supervisor 死亡检出——重启序列启动")
            if (ensureSupervisor()) {
                restarts.addLast(System.currentTimeMillis())
                trace(NodeType.SYSTEM_EVENT, "termux_restart: ok=${restarts.size}/3@10min")
            } else {
                trace(NodeType.ERROR, "termux_restart: 失败（下轮再试）")
            }
        }
    }

    // ═══ M3.0：工作站 FC 实现（§15 三通道 → supervisor HTTP）═══

    /** app 层工作目录流面注入位（WorkDirRepository 在 app 模块 services 不可见——同 ShizukuBridge.gestureDispatcher 注入先例） */
    @Volatile var workDirStreams: WorkDirFileStreams? = null
    fun attachWorkDirStreams(streams: WorkDirFileStreams?) { workDirStreams = streams }

    /** 工作目录文件推拉接口（app 层适配 WorkDirRepository；linux_upload/linux_download 消费） */
    interface WorkDirFileStreams {
        /** 打开读流：null = 未找到/未配置；Pair(流, 字节长度；未知 -1)——调用方负责 close */
        suspend fun openRead(fileName: String): Pair<java.io.InputStream, Long>?
        /** 流式写入 SAF；返回 (error=null 成功, finalName 实际落点名, bytes 字节数) */
        suspend fun writeStream(subdir: String, fileName: String, mime: String,
                                maxBytes: Long, input: java.io.InputStream): WorkDirWriteResult
    }
    data class WorkDirWriteResult(val error: String?, val finalName: String, val bytes: Long)

    private fun supUrl(path: String): String =
        "http://127.0.0.1:${(config as TermuxBridgeConfig).supervisorPort}$path"

    /** supervisor 请求（超时按调用定制——/exec 可达 maxExecTimeoutS；失败 null，E28 由调用方埋点） */
    private suspend fun supRequest(request: Request, timeoutMs: Long): Response? =
        withContext(Dispatchers.IO) {
            try {
                if (!pairSupervisor()) return@withContext null
                httpClient.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(authorizeSupervisorRequest(request)).execute().also {
                        if (it.code == java.net.HttpURLConnection.HTTP_UNAUTHORIZED) controlToken = null
                    }
            } catch (_: Exception) { null }
        }

    private fun unavailable(msg: String, functionName: String? = null): FunctionResult {
        trace(NodeType.ERROR, "linux_fc_unavailable: $msg", functionName)
        return FunctionResult.failure(msg)
    }

    /** 前置：Termux 在装 + supervisor 活（自愈拉起）——不满足即 B11 引导文案 */
    private suspend fun ensureWorkstation(): String? {
        if (!isTermuxInstalled()) return guideText()
        if (!ensureSupervisor()) return "supervisor 不可用（已尝试拉起）——见运行日志 termux_command"
        return null
    }

    /** linux_exec：POST /exec（cwd 粘性 session 隔离在 supervisor 侧 P1-2；超时 ceiling=§9.1 maxExecTimeoutS） */
    private suspend fun linuxExec(args: Map<String, Any?>): FunctionResult = withContext(Dispatchers.IO) {
        val cmd = args["cmd"] as? String ?: return@withContext FunctionResult.failure("cmd 必填")
        ensureWorkstation()?.let { return@withContext FunctionResult.failure(it) }
        val cfg = config as TermuxBridgeConfig
        val timeoutS = ((args["timeout_s"] as? Number)?.toInt() ?: 120).coerceIn(1, cfg.maxExecTimeoutS)
        val body = JSONObject().put("cmd", cmd).put("timeout_s", timeoutS).apply {
            (args["cwd"] as? String)?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            (args["session_id"] as? String)?.takeIf { it.isNotBlank() }?.let { put("session_id", it) }
        }
        val resp = supRequest(Request.Builder().url(supUrl("/exec"))
            .post(body.toString().toRequestBody(JSON_BODY_MEDIA)).build(),
            (timeoutS + 15) * 1000L) ?: return@withContext unavailable("exec 无响应（supervisor 断连）", "linux_exec")
        resp.use { r ->
            if (!r.isSuccessful) return@withContext unavailable("exec HTTP ${r.code}", "linux_exec")
            val o = try { JSONObject(r.body?.string() ?: "{}") } catch (_: Exception) {
                return@withContext unavailable("exec 响应解析失败", "linux_exec")
            }
            o.optJSONObject("error")?.let {
                return@withContext unavailable(
                    "exec 错误: ${it.optString("error").take(60)}", "linux_exec")
            }
            val out = o.optString("stdout").trim()
            val err = o.optString("stderr").trim()
            val exit = o.optInt("exit", -1)
            val cwd = o.optString("cwd")
            val timedOut = o.optBoolean("timed_out")
            val sb = StringBuilder(out)
            if (err.isNotBlank()) sb.append("\n[stderr]\n").append(err)
            sb.append("\n[exit=$exit cwd=$cwd]")
            if (timedOut) sb.append("\n⏱ 超时，进程清理确认=").append(o.optBoolean("cleanup_ok"))
            o.optJSONObject("output_files")?.let { files ->
                for (stream in listOf("stdout", "stderr")) {
                    val file = files.optJSONObject(stream) ?: continue
                    if (file.optLong("bytes") > EXEC_OUTPUT_TAIL_BYTES) {
                        sb.append("\n[").append(stream).append("原文: ").append(file.optString("path"))
                            .append("，已存/总字节=").append(file.optLong("saved_bytes"))
                            .append('/').append(file.optLong("bytes"))
                            .append("；仅保留近期文件，可用 linux_read 分页读取]")
                    }
                }
            }
            trace(NodeType.SYSTEM_EVENT,
                "linux_exec: exit=$exit out=${out.length}ch err=${err.length}ch timeout=${if (timedOut) "Y" else "N"} cmd=${cmd.take(50)}",
                "linux_exec")
            FunctionResult.success(sb.toString().ifBlank { "（无输出）" })
        }
    }

    private suspend fun linuxLs(args: Map<String, Any?>): FunctionResult = withContext(Dispatchers.IO) {
        val path = (args["path"] as? String).orEmpty().ifBlank { "/root/work" }
        ensureWorkstation()?.let { return@withContext FunctionResult.failure(it) }
        val resp = supRequest(Request.Builder().url(supUrl("/ls?path=${urlEnc(path)}")).build(),
            10_000L) ?: return@withContext unavailable("ls 无响应", "linux_ls")
        resp.use { r ->
            val o = try { JSONObject(r.body?.string() ?: "{}") } catch (_: Exception) { null }
                ?: return@withContext unavailable("ls 响应解析失败", "linux_ls")
            val entries = o.optJSONArray("entries")
                ?: return@withContext unavailable(
                    "ls 错误: ${o.optJSONObject("error")?.optString("error") ?: o}", "linux_ls")
            val sb = StringBuilder("[${o.optString("path")}] ${entries.length()} 项\n")
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                sb.append(if (e.optBoolean("is_dir")) "📁 " else "   ")
                    .append(e.optString("name"))
                    .append(if (e.optBoolean("is_dir")) "/" else "  ${e.optLong("size")}B")
                    .append('\n')
            }
            FunctionResult.success(sb.toString())
        }
    }

    private suspend fun linuxReadFile(args: Map<String, Any?>): FunctionResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String ?: return@withContext FunctionResult.failure("path 必填")
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        ensureWorkstation()?.let { return@withContext FunctionResult.failure(it) }
        val resp = supRequest(
            Request.Builder().url(supUrl("/read?path=${urlEnc(path)}&offset=$offset")).build(),
            10_000L) ?: return@withContext unavailable("read 无响应", "linux_read_file")
        resp.use { r ->
            val o = try { JSONObject(r.body?.string() ?: "{}") } catch (_: Exception) { null }
                ?: return@withContext unavailable("read 响应解析失败", "linux_read_file")
            val text = o.optString("text")
            if (o.has("error")) return@withContext unavailable(
                "read 错误: ${o.optJSONObject("error")?.optString("error")}", "linux_read_file")
            FunctionResult.success(text + "\n[size=${o.optLong("size")}B offset=${o.optInt("offset")}——未读完续传 offset]")
        }
    }

    /** 小文本直写：POST /file/upload RAW body（M2.1b 分流通道）——大文件走 linux_upload */
    private suspend fun linuxWriteFile(args: Map<String, Any?>): FunctionResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String ?: return@withContext FunctionResult.failure("path 必填")
        val content = args["content"] as? String ?: return@withContext FunctionResult.failure("content 必填")
        if (content.length > 262_144) return@withContext FunctionResult.failure(
            "内容 ${content.length / 1024}KB 超 256KB 上限——大文件用 linux_upload")
        ensureWorkstation()?.let { return@withContext FunctionResult.failure(it) }
        val resp = supRequest(Request.Builder().url(supUrl("/file/upload?path=${urlEnc(path)}"))
            .post(content.toByteArray(Charsets.UTF_8).toRequestBody(OCTET_MEDIA)).build(),
            15_000L) ?: return@withContext unavailable("write 无响应", "linux_write_file")
        resp.use { r ->
            val o = try { JSONObject(r.body?.string() ?: "{}") } catch (_: Exception) { null }
                ?: return@withContext unavailable("write 响应解析失败", "linux_write_file")
            if (!o.optBoolean("ok")) return@withContext unavailable(
                "write 失败: ${o.optJSONObject("error")?.optString("error") ?: o}", "linux_write_file")
            trace(
                NodeType.SYSTEM_EVENT,
                "linux_write: ${o.optString("path")} ${o.optLong("size")}B", "linux_write_file")
            FunctionResult.success("✅ 已写入 ${o.optString("path")}（${o.optLong("size")}B）")
        }
    }

    /** SAF → 工作站：openReadStream → PUT /file/upload 流式 body（长度已知定长/未知 chunked） */
    private suspend fun linuxUpload(args: Map<String, Any?>): FunctionResult = withContext(Dispatchers.IO) {
        val fromSaf = args["from_saf"] as? String ?: return@withContext FunctionResult.failure("from_saf 必填")
        val toLinux = args["to_linux"] as? String ?: return@withContext FunctionResult.failure("to_linux 必填")
        val streams = workDirStreams
            ?: return@withContext unavailable("工作目录服务未连接（无障碍 AgentRuntimeService 未运行）——重启无障碍后重试", "linux_upload")
        val (input, len) = streams.openRead(fromSaf)
            ?: return@withContext FunctionResult.failure("工作目录未找到「$fromSaf」（或读取失败）——确认文件引用")
        ensureWorkstation()?.let { input.close(); return@withContext FunctionResult.failure(it) }
        try {
            val body = object : RequestBody() {
                override fun contentType() = OCTET_MEDIA
                override fun contentLength() = len
                override fun writeTo(sink: BufferedSink) {
                    val buf = ByteArray(64 * 1024)
                    input.use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            sink.write(buf, 0, n)
                        }
                    }
                }
            }
            // POST（supervisor 仅实现 do_POST——PUT 会 501，M3.0 L3 实测踩坑）
            val resp = supRequest(Request.Builder().url(supUrl("/file/upload?path=${urlEnc(toLinux)}"))
                .post(body).build(), 600_000L)
                ?: return@withContext unavailable("upload 无响应", "linux_upload")
            resp.use { r ->
                val o = try { JSONObject(r.body?.string() ?: "{}") } catch (_: Exception) { null }
                    ?: return@withContext unavailable("upload 响应解析失败", "linux_upload")
                if (!o.optBoolean("ok")) return@withContext unavailable(
                    "upload 失败: ${o.optJSONObject("error")?.optString("error") ?: o}", "linux_upload")
                trace(
                    NodeType.SYSTEM_EVENT,
                    "linux_upload: $fromSaf → ${o.optString("path")} ${o.optLong("size")}B", "linux_upload")
                FunctionResult.success("✅ 已上传 $fromSaf → ${o.optString("path")}（${o.optLong("size")}B）")
            }
        } finally {
            runCatching { input.close() }
        }
    }

    /** 工作站 → SAF：GET /file?path= Range 流 → WorkDirRepository.writeBinaryStream（linux/ 子目录） */
    private suspend fun linuxDownload(args: Map<String, Any?>): FunctionResult = withContext(Dispatchers.IO) {
        val fromLinux = args["from_linux"] as? String ?: return@withContext FunctionResult.failure("from_linux 必填")
        val toSaf = (args["to_saf"] as? String).orEmpty().ifBlank { fromLinux.substringAfterLast('/') }
        val streams = workDirStreams
            ?: return@withContext unavailable("工作目录服务未连接（无障碍 AgentRuntimeService 未运行）——重启无障碍后重试", "linux_download")
        ensureWorkstation()?.let { return@withContext FunctionResult.failure(it) }
        val resp = supRequest(Request.Builder().url(supUrl("/file?path=${urlEnc(fromLinux)}")).build(),
            600_000L) ?: return@withContext unavailable("download 无响应", "linux_download")
        resp.use { r ->
            if (!r.isSuccessful) return@withContext unavailable("download HTTP ${r.code}（路径不存在？）", "linux_download")
            val len = r.body?.contentLength() ?: -1L
            if (len > MAX_TRANSFER_BYTES) return@withContext FunctionResult.failure(
                "文件 ${len / 1_048_576}MB 超上限（${MAX_TRANSFER_BYTES / 1_048_576}MB）")
            val body = r.body ?: return@withContext unavailable("download 空响应", "linux_download")
            val result = streams.writeStream("linux", sanitize(toSaf), "application/octet-stream",
                MAX_TRANSFER_BYTES, body.byteStream())
            if (result.error != null) return@withContext FunctionResult.failure(result.error)
            trace(
                NodeType.SYSTEM_EVENT,
                "linux_download: $fromLinux → linux/${result.finalName} ${result.bytes}B", "linux_download")
            FunctionResult.success("✅ 已下载到工作目录/linux/${result.finalName}（${result.bytes / 1024}KB）")
        }
    }

    private fun urlEnc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** SAF 落点名净化（WebFetchModule.sanitizeName 同律——仅相对名防穿越） */
    private fun sanitize(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("[/\\\\:*?\"<>|%#]"), "_")
        if (name.isBlank() || name == "." || name == "..") name = "linux_${System.currentTimeMillis()}"
        return name.take(120)
    }

    // ═══ 结果回传接收 ═══

    @SuppressLint("UnspecifiedRegisterReceiverFlag")  // API<33 走无 flag 分支
    private fun ensureResultReceiver() {
        if (resultReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { handleResult(intent) }
        }
        val filter = IntentFilter(ACTION_RESULT)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // PendingIntent.send 以创建方（本应用）身份执行——NOT_EXPORTED 应可收；
                // **L3 实测项**：若真机收不到回传改 RECEIVER_EXPORTED（广播仅命令输出，无敏感数据）
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            resultReceiver = receiver
        } catch (e: Exception) {
            trace(NodeType.ERROR, "receiver_register_failed: ${e.message?.take(60)}")
        }
    }

    private fun handleResult(intent: Intent?) {
        val token = intent?.getStringExtra(EXTRA_TOKEN) ?: return
        val cb = pendingResults.remove(token) ?: return
        val bundle: Bundle? = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
        val stdout = bundle?.getString(RESULT_STDOUT).orEmpty()
        val stderr = bundle?.getString(RESULT_STDERR).orEmpty()
        val exit = bundle?.getInt(RESULT_EXIT_CODE, -1) ?: -1
        val errmsg = bundle?.getString(RESULT_ERRMSG)
        if (errmsg != null) trace(NodeType.ERROR, "result_err: token=$token errmsg=${errmsg.take(80)}")
        trace(NodeType.SYSTEM_EVENT, "result: token=$token exit=$exit out=${stdout.length}ch err=${stderr.length}ch")
        // stderr 并入输出（一次性脚本与编译校验的诊断回路——行号错误靠它回传）
        val out = if (stderr.isBlank()) stdout else "$stdout\n[stderr]\n$stderr"
        cb(out.trim(), exit)
    }

    /**
     * 结果 PendingIntent（广播 flavor）：Termux 经 PendingIntent.send(context, code, fillIn) 回传——
     * flavor 无关（官方示例 getService，广播同契约）；FLAG_MUTABLE 必须（S+ fill-in 需可变）；
     * FLAG_ONE_SHOT + 唯一 requestCode（官方 wiki：复用 requestCode 只回首次结果）。
     */
    private fun buildResultPendingIntent(token: String): PendingIntent {
        val resultIntent = Intent(ACTION_RESULT)
            .setPackage(appContext.packageName)
            .putExtra(EXTRA_TOKEN, token)
        var flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(appContext, requestIdCounter.incrementAndGet(), resultIntent, flags)
    }

    private fun trace(type: NodeType, msg: String, functionName: String? = null) {
        traceManager.record(AgentTraceEvent(
            traceId = TRACE_ID,
            nodeType = type,
            sourceModule = metadata.id,
            summary = msg,
            functionName = functionName
        ))
    }

    companion object {
        private const val MEDIA_READ_TIMEOUT_MS = 30_000L
        private const val TRACE_ID = "termux_command"
        const val TERMUX_PACKAGE = "com.termux"

        // ── RUN_COMMAND 契约（TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE 官方字面量）──
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        // ── 结果回传（TermuxConstants.TERMUX_APP.TERMUX_SERVICE 命名空间）──
        private const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
        private const val EXEC_OUTPUT_TAIL_BYTES = 64 * 1024
        private const val RESULT_STDOUT = "stdout"
        private const val RESULT_STDERR = "stderr"
        private const val RESULT_EXIT_CODE = "exitCode"
        private const val RESULT_ERRMSG = "errmsg"

        // ── Termux 路径约定（TermuxConstants.TERMUX_PREFIX_DIR_PATH）──
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val TERMUX_BIN = "$TERMUX_PREFIX/bin"
        const val PYTHON3 = "$TERMUX_BIN/python3"   // bootstrap / healthCheck
        const val BASH = "$TERMUX_BIN/bash"         // bootstrap 脚本载体
        private const val ZERO_TOOL = "zero_tool.py"
        private const val ZERO_SUPERVISOR = "zero_supervisor.py"
        private const val ZERO_RELEASE = "zero_release.py"
        private const val ZERO_HOST = "zero_host.py"
        private const val SUPERVISOR_START_TIMEOUT_MS = 90_000L
        private const val SUPERVISOR_HEALTH_POLL_MS = 500L

        /** bootstrap 一次性引导超时（20min 常数——下载 Debian rootfs+apt，M15 律不进 Config） */
        private const val BOOTSTRAP_TIMEOUT_MS = 1_200_000L

        /** 工作站↔SAF 单文件传输上限（512MB 常数——素材加工量级，M15 律不进 Config） */
        private const val MAX_TRANSFER_BYTES = 512L * 1024 * 1024
        private val OCTET_MEDIA = "application/octet-stream".toMediaType()
        private val JSON_BODY_MEDIA = "application/json".toMediaType()

        // ── 本应用回传路由 ──
        private const val ACTION_RESULT = "com.zero.v2.app.TERMUX_CMD_RESULT"
        private const val EXTRA_TOKEN = "zero_token"
    }
}

/**
 * Termux 工作站配置（落地计划 §9.1 参数决策表 · TermuxBridge 四字段）。
 * supervisorPort/maxExecTimeoutS 由 M2.0/M3.0 消费；installTimeoutS 由 M3.1（manifest deps）消费。
 */
class TermuxBridgeConfig : ModuleConfigBase() {

    @ConfigField(label = "supervisor 端口", fieldType = ConfigFieldType.NUMBER,
        description = "supervisor 控制面固定端口（HTTP 直连探活，M2.0）", min = 1024.0, max = 65535.0)
    var supervisorPort: Int = 7878

    @ConfigField(label = "exec 超时上限(秒)", fieldType = ConfigFieldType.NUMBER,
        description = "linux_exec 的 timeout_s 天花板——防 LLM 传超长挂死回合（M3.0）", min = 10.0, max = 3600.0)
    var maxExecTimeoutS: Int = 600

    @ConfigField(label = "依赖安装超时(秒)", fieldType = ConfigFieldType.NUMBER,
        description = "模块 manifest deps 的 pip/apt 安装超时（与探活超时分离，M3.1）", min = 30.0, max = 1800.0)
    var installTimeoutS: Int = 300

    @ConfigField(label = "启动自动引导", fieldType = ConfigFieldType.TOGGLE,
        description = "启动时自动 bootstrap（装 Termux pkg + Debian 工作站——首跑下载量大，默认手动：" +
            "在本页打开此开关立即触发；引导幂等，装过即跳过）")
    var autoBootstrapOnStart: Boolean = false

    @ConfigField(label = "崩溃自动停阈值(K)", fieldType = ConfigFieldType.NUMBER,
        description = "模块跨会话崩溃计数超 K → 自动停用 + 权限中心告警（A-II-3 全局层；" +
            "告警后再启用=重新计数）", min = 2.0, max = 20.0)
    var crashAutoDisableK: Int = 5

    /** E3：紧急总开关——独立于 LLM/模块/网络（设计§5）。开启即阻断所有动态模块 activate + 路由，仅用户可解除。 */
    @ConfigField(label = "紧急停止", fieldType = ConfigFieldType.TOGGLE,
        description = "动态模块紧急总开关。开启即阻断所有动态模块部署/激活与路由（独立于 LLM/模块/网络，" +
            "仅用户可解除）。用于发现异常时立即冻结自迭代——开启后活动版本保留不删，仅停路由。默认关闭")
    var emergencyStop: Boolean = false

    override fun toJson(): String = JSONObject().apply {
        put("supervisorPort", supervisorPort)
        put("maxExecTimeoutS", maxExecTimeoutS)
        put("installTimeoutS", installTimeoutS)
        put("autoBootstrapOnStart", autoBootstrapOnStart)
        put("crashAutoDisableK", crashAutoDisableK)
        put("emergencyStop", emergencyStop)
    }.toString()

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            supervisorPort = obj.optInt("supervisorPort", 7878).coerceIn(1024, 65535)
            maxExecTimeoutS = obj.optInt("maxExecTimeoutS", 600).coerceIn(10, 3600)
            installTimeoutS = obj.optInt("installTimeoutS", 300).coerceIn(30, 1800)
            autoBootstrapOnStart = obj.optBoolean("autoBootstrapOnStart", false)
            crashAutoDisableK = obj.optInt("crashAutoDisableK", 5).coerceIn(2, 20)
            emergencyStop = obj.optBoolean("emergencyStop", false)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = TermuxBridgeConfig()
}
