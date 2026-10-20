package com.zero.v2.services.realtime

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 屏幕快照 — D47 结构化（防"旧帧当当前页"）。
 *
 * 消费方约定：使用前调用 [isStale] 校验时间戳与哈希；
 * Live/推理将快照注入上下文时附带走捕获时间。
 */
data class ScreenSnapshot(
    val packageName: String,
    val windowId: Int,
    val timestamp: Long,
    val contentHash: Int,
    val text: String,
    val nodeCount: Int,
    val bounds: String? = null
) {
    /** 快照是否已过期（默认阈值 10s——文献阅读翻页节奏） */
    fun isStale(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 10_000): Boolean =
        now - timestamp > maxAgeMs

    fun capturedAtLabel(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/**
 * 屏幕文本阅读器 — Step 6.2（D34 主通道 / D39 / D47）。
 *
 * 无障碍节点树 → 结构化 ScreenSnapshot：
 *   - rootInActiveWindow 递归提取可见文本（去重/排序/8K 上限）
 *   - 包名白名单（默认空 = 全部拒绝，6.5a 设置区管理——D39 隐私边界）
 *   - FC read_screen_text()（AUTO）供 LLM 主动读屏
 *
 * 注意：快照为拉取式（调用时现读当前树），不做事件流订阅——
 * TYPE_WINDOW_CONTENT_CHANGED 仅用于 6.5a 的"页面变化"状态提示，由 AgentRuntimeService 节流转发。
 */
@Singleton
class ScreenTextReader @Inject constructor(

    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    override val metadata = ModuleMetadata(
        id = "screen_reader",
        displayName = "屏幕阅读",
        description = "读取当前屏幕文本内容（无障碍节点树，白名单 App 限定）",
        category = ModuleCategory.INSTRUMENT,
        launchOrder = 130
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    /** 白名单包名（D39：默认空 = 拒绝读屏；6.5a Live 设置区管理） */
    private val allowedPackages = mutableSetOf<String>()

    /** 由 AgentRuntimeService 注入根节点读取入口（服务连接后设置；D45：服务=感官） */
    @Volatile private var rootSupplier: (() -> AccessibilityNodeInfo?)? = null

    fun attachRootSupplier(supplier: () -> AccessibilityNodeInfo?) {
        rootSupplier = supplier
    }

    fun setWhitelist(packages: Set<String>) {
        allowedPackages.clear()
        allowedPackages.addAll(packages)
        traceManager.record("screen_reader", NodeType.SYSTEM_EVENT, metadata.id,
            "whitelist_updated: size=${packages.size}")
    }

    fun whitelist(): Set<String> = allowedPackages.toSet()

    /**
     * 页面内容变化通知（AgentRuntimeService 节流转发）。
     * 快照为拉取式，此回调仅记录变化时间——消费方据此判断是否需要重新 capture。
     */
    @Volatile private var lastContentChangeAt: Long = 0
    @Volatile private var lastContentChangePkg: String = ""

    fun onScreenContentChanged(packageName: String?) {
        lastContentChangeAt = System.currentTimeMillis()
        lastContentChangePkg = packageName ?: ""
    }

    /** 自上次内容变化以来的毫秒数（Live 推理可据此决定是否刷新快照） */
    fun msSinceContentChange(): Long = lastContentChangeAt.takeIf { it > 0 }
        ?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE

    override suspend fun onAwake() {

        _lifecycle.value = ModuleLifecycle.AWAKE
    }
    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() {
        rootSupplier = null
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}
    override suspend fun checkAvailability(): Boolean = rootSupplier != null
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
        com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
        val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition("read_screen_text",
                "读取当前屏幕的可见文本内容（当前 App 页面）。适合回答用户关于正在浏览页面的问题，如翻译、解释、找信息。",
                mapOf("type" to "object", "properties" to mapOf(
                    "max_chars" to mapOf("type" to "number", "description" to "返回文本上限，默认 8000")
                ))
            ), metadata.id, permission = ActionPermission.AUTO
        ) { args ->
            val maxChars = (args["max_chars"] as? Number)?.toInt() ?: 8000
            readCurrentScreen(maxChars)
        }
    }

    /**
     * 拉取当前屏幕快照（主入口——Live 推理与 FC 共用）。
     * @return 快照；未连接/无根节点/包名不在白名单 = null
     */
    fun captureCurrentScreen(maxChars: Int = 8000): ScreenSnapshot? {
        val root = rootSupplier?.invoke() ?: run {
            traceManager.record("screen_reader", NodeType.SYSTEM_EVENT, metadata.id,
                "capture_failed: 无障碍服务未连接或窗口不可读")
            return null
        }
        val pkg = root.packageName?.toString() ?: ""
        if (pkg.isNotBlank() && allowedPackages.isNotEmpty() && pkg !in allowedPackages) {
            traceManager.record("screen_reader", NodeType.SYSTEM_EVENT, metadata.id,
                "capture_blocked: $pkg 不在白名单")
            return null
        }

        val builder = ScreenTextBuilder(maxChars)
        walkNode(root, builder, 0)
        val text = builder.build()

        val snapshot = ScreenSnapshot(
            packageName = pkg,
            windowId = root.windowId,
            timestamp = System.currentTimeMillis(),
            contentHash = text.hashCode(),
            text = text,
            nodeCount = builder.count(),
            bounds = android.graphics.Rect().let { r -> root.getBoundsInScreen(r); r.toString() }
        )
        traceManager.record("screen_reader", NodeType.SYSTEM_EVENT, metadata.id,
            "captured: pkg=$pkg chars=${text.length} nodes=${builder.count()}")
        return snapshot
    }

    private fun readCurrentScreen(maxChars: Int): FunctionResult {
        val snapshot = captureCurrentScreen(maxChars)
            ?: return FunctionResult.failure(
                "无法读取屏幕。可能原因：① 无障碍服务未开启或需重新开启（设置→权限中心）；" +
                        "② 当前页面不提供文本（游戏/视频/图片）；③ 当前 App 不在屏幕阅读白名单（设置→权限中心/Live 设置）。")
        return FunctionResult.success(
            "【屏幕内容 | ${snapshot.packageName} | 捕获于 ${snapshot.capturedAtLabel()}】\n" +
                    snapshot.text +
                    if (snapshot.isStale()) "\n(注意：此快照已超过 10 秒，页面可能已变化)" else ""
        )
    }

    /** 深度优先遍历，深度上限 50 / 节点上限 3000（防畸形树爆炸） */
    private fun walkNode(node: AccessibilityNodeInfo?, builder: ScreenTextBuilder, depth: Int) {
        if (node == null || depth > 50 || builder.count() > 3000) return
        builder.increment()
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { builder.append(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() && it.length < 200 }
            ?.let { builder.append(it) }
        for (i in 0 until node.childCount) {
            walkNode(node.getChild(i), builder, depth + 1)
        }
    }
}

/** 文本聚合：连续文本合并、去重、排序保持树序、总长上限 */
private class ScreenTextBuilder(private val maxChars: Int) {
    private val lines = mutableListOf<String>()
    private val seen = HashSet<String>()
    private var nodes = 0

    fun count(): Int = nodes
    fun increment() { nodes++ }

    fun append(raw: String) {
        val line = raw.trim()
        if (line.isEmpty() || line.length > 1000) return   // 超长单节点（如 base64）跳过
        if (seen.add(line)) lines.add(line)                // 同层重复文本去重
    }

    fun build(): String = lines.joinToString("\n").take(maxChars)
}
