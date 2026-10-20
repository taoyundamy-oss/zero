package com.zero.v2.services.module

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.data.dao.DynamicModuleDao
import com.zero.v2.core.data.entity.DynamicModuleEntity
import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ModuleCategory
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.core.moduleapi.ModuleMetadata
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.mcp.McpClientModule
import com.zero.v2.services.termux.CompileManager
import com.zero.v2.services.termux.ReleaseManager
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 代码开发元工具 — M3.2（D67 最大授权：全 AUTO 零弹窗；D69 工作站形态）。
 *
 * LLM 自迭代闭环：develop_module → CompileManager（写码/语法门/manifest/deps/探活）
 * → 通过即挂载（McpClientModule.mountDynamicModule——dyn_* FC 恒 AUTO）→ 返回精确工具名
 * 供立即调用。失败文本（stderr 行号/探活原因）原样回 LLM——M3.3 接有界修复回路。
 *
 * 元工具排除自动化递归自引用（§3.5 精神）：框架无元标志位（不加字段=不动封板接口）——
 * 落三层软硬结合：FC description 明示禁自动化递归 + maxModules 硬顶（P1-8）+
 * M4.3 可控性审计复核。E28：code_dev trace 全路径（generate/compile/test/mount/repair §6.2）。
 */
@Singleton
class CodeDevModule @Inject constructor(
    private val compileManager: CompileManager,
    private val releaseManager: ReleaseManager,
    private val mcpClient: McpClientModule,
    private val dao: DynamicModuleDao,
    private val toolCatalog: com.zero.v2.core.moduleapi.FunctionCatalog,

    private val traceManager: AgentTraceManager
) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {

    override val metadata = ModuleMetadata(
        id = "code_dev",
        displayName = "代码开发",
        description = "LLM 自迭代元工具：开发/更新动态模块（Termux Debian 工作站），挂载即用零弹窗（D67）",
        category = ModuleCategory.INSTRUMENT
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()
    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()
    override val config = CodeDevModuleConfig()  // §9.1 三字段

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE

    }

    override suspend fun onStart() { _lifecycle.value = ModuleLifecycle.STARTED }
    override suspend fun onDestroy() { _lifecycle.value = ModuleLifecycle.DESTROYED }
    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) { consecutiveFailures.clear() }
    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    private fun trace(type: NodeType, msg: String) {
        traceManager.record(TRACE_ID, type, metadata.id, msg)
    }

    override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
            com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
            val functionRegistry = this
        functionRegistry.register(
            FunctionDefinition(
                name = "develop_module",
                description = DEVELOP_GUIDE,
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string",
                            "description" to "模块名：小写字母开头仅 a-z0-9_（≤40）；同名再调用=更新该模块"),
                        "description" to mapOf("type" to "string", "description" to "模块用途一句话"),
                        "code" to mapOf("type" to "string", "description" to "完整 Python 源码（UTF-8）"),
                        "config_schema" to mapOf("type" to "string",
                            "description" to "可选：配置声明 JSON 数组字符串（code 内 ZERO_MANIFEST 已声明时以 manifest 为准）")
                    ),
                    "required" to listOf("name", "description", "code")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO  // D67
        ) { args -> handleDevelop(args) }

        functionRegistry.register(
            FunctionDefinition(
                name = "list_modules",
                description = "列出全部动态模块（id/状态/来源/依赖/已注册工具数）",
                parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { handleList() }

        functionRegistry.register(
            FunctionDefinition(
                name = "enable_module",
                description = "启用动态模块（探活后挂载——dyn_* 工具即刻可用；CRASHED 模块用户明确重试语义）",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf("id" to mapOf("type" to "string", "description" to "模块 id")),
                    "required" to listOf("id")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> handleToggle(args, enable = true) }

        functionRegistry.register(
            FunctionDefinition(
                name = "disable_module",
                description = "停用动态模块（停进程+注销工具；源码保留可再启用）",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf("id" to mapOf("type" to "string", "description" to "模块 id")),
                    "required" to listOf("id")
                )
            ),
            metadata.id, permission = ActionPermission.AUTO
        ) { args -> handleToggle(args, enable = false) }
    }

    // ═══ develop_module 主管线（§5.1 ①-⑥ + ⑤' 有界修复回路 M3.3）═══

    /** 同模块连续失败计数（成功即清零；超 repairMaxRounds 拒收——§9.1，防 LLM 无界烧 token） */
    private val consecutiveFailures = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()  // (轮次, 最后原因)

    /**
     * 失败回执统一口：埋 repair trace + 计轮 + 超限终局文案（"模块未挂载：原因"——§5.1 ⑤）。
     * 失败正文（stderr 行号/traceback）原样附上——LLM 下一轮据此修正（JsonExtractor 针对
     * LLM JSON 输出场景；code 参数为原生字符串，无需提取——直传即 §7.6 精神的最短路）。
     */
    private fun failBounded(name: String, stage: String, detail: String): FunctionResult {
        val cfg = config as CodeDevModuleConfig
        val prev = consecutiveFailures[name]
        val round = (prev?.first ?: 0) + 1
        consecutiveFailures[name] = round to detail
        return if (round > cfg.repairMaxRounds) {
            trace(NodeType.ERROR, "repair_exceeded: $name rounds=$round>${cfg.repairMaxRounds} stage=$stage")
            FunctionResult.failure(
                "模块未挂载：已连续 $round 轮未通过（上限 ${cfg.repairMaxRounds}，$stage 阶段）。\n" +
                    "最后原因：${detail.take(300)}\n请换实现思路（简化代码/核对依赖/改用 linux_exec 先验证逻辑），稍后再重试 develop_module。")
        } else {
            trace(NodeType.SYSTEM_EVENT, "repair: $name round=$round/${cfg.repairMaxRounds} stage=$stage")
            FunctionResult.failure("❌ $stage 未过（第 $round/${cfg.repairMaxRounds} 轮）：\n$detail\n——按错误修正后重调 develop_module（同名=更新）")
        }
    }

    private suspend fun handleDevelop(args: Map<String, Any?>): FunctionResult =
        releaseManager.operationsMutex.withLock { publishCandidate(args) }

    // Input rejection exits precede every filesystem/process side effect.
    @Suppress("ReturnCount")
    private suspend fun publishCandidate(args: Map<String, Any?>): FunctionResult {
        val cfg = config as CodeDevModuleConfig
        val name = args["name"] as? String ?: return FunctionResult.failure("name 必填")
        val code = args["code"] as? String ?: return FunctionResult.failure("code 必填")
        if ((consecutiveFailures[name]?.first ?: 0) >= cfg.repairMaxRounds) {
            return FunctionResult.failure("连续修复已达上限；请检查错误并保存代码开发模块配置以开始新一轮")
        }
        trace(NodeType.SYSTEM_EVENT, "generate: $name ${code.length}ch")

        // P1-8 上限（更新既有模块不计新名额）
        val existing = dao.getById(name)
        if (existing == null) {
            val total = try { dao.observeAll().first().size } catch (_: Exception) { 0 }
            if (total >= cfg.maxModules) {
                trace(NodeType.ERROR, "develop_rejected: $name 模块数超上限 ${cfg.maxModules}")
                return FunctionResult.failure("动态模块数已达上限 ${cfg.maxModules}——先停用/清理不再使用的模块")
            }
        }

        // ①②③ 写码 + 语法门 + manifest（失败文本含 stderr 行号——回 LLM 修复）
        var manifest = compileManager.parseManifest(code)
            ?: return failBounded(name, "manifest", "ZERO_MANIFEST 必须为合法 JSON，runtime 必须为 python")

        // config_schema 参数补充（manifest 未声明时才用参数；坏 JSON 即拒）
        val schemaParam = (args["config_schema"] as? String)?.trim().orEmpty()
        if (manifest.configSchema == "[]" && schemaParam.isNotBlank()) {
            val valid = try { JSONArray(schemaParam); true } catch (_: Exception) { false }
            if (!valid) return FunctionResult.failure("config_schema 不是合法 JSON 数组（应如 [{\"key\":...,\"type\":\"text|number|toggle\"}]）")
            manifest = manifest.copy(configSchema = schemaParam)
        }

        return deployAndMount(name, code, manifest, existing)
    }

    private suspend fun deployAndMount(
        name: String, code: String, manifest: CompileManager.ModuleManifest, existing: DynamicModuleEntity?,
    ): FunctionResult {
        val declaration = manifest.toReleaseManifest((config as CodeDevModuleConfig).probeTimeoutS)
        val release = releaseManager.publish(name, code, declaration)
        if (!release.ok) return failBounded(name, "发布", release.error ?: "部署失败，查看回滚报告")
        val entity = DynamicModuleEntity(
            id = name,
            origin = existing?.origin ?: "GENERATED",  // 同名更新保原 origin（手写不被覆盖标记）
            source = code,
            schema = JSONObject()
                .put("deps", JSONArray(manifest.deps))
                .put("termuxDeps", JSONArray(manifest.termuxDeps))
                .put("runtime", manifest.runtime).toString(),
            transport = declaration.transport,
            lastEndpoint = "",
            deps = DynamicModuleEntity.toJsonList(manifest.deps),
            installedDeps = DynamicModuleEntity.toJsonList(manifest.deps),
            termuxDeps = DynamicModuleEntity.toJsonList(manifest.termuxDeps),
            runtime = manifest.runtime,
            configSchema = manifest.configSchema,
            state = "DISABLED",
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastCrashAt = existing?.lastCrashAt ?: 0
        )
        dao.upsert(entity)

        // ⑤ 试跑探活（probeTimeoutS §9.1——P1-7 只计启动→端点，安装已另计）
        // ⑥ 挂载（同名 start=旧进程停→新起；dyn_* 恒 AUTO）
        return if (!mcpClient.mountDynamicModule(entity)) {
            trace(NodeType.ERROR, "mount_failed: $name")
            val rollback = releaseManager.rollbackRelease(name)
            if (existing != null && rollback.ok) {
                dao.upsert(existing)
                mcpClient.mountDynamicModule(existing)
            }
            failBounded(name, "挂载", "挂载失败；回滚成功=${rollback.ok}，${rollback.error.orEmpty()}")
        } else {
            consecutiveFailures.remove(name)
            mountedResult(name, manifest)
        }
    }

    private fun mountedResult(name: String, manifest: CompileManager.ModuleManifest): FunctionResult {
        val tools = toolCatalog.getAllDefinitions()
            .filter { it.name.startsWith("dyn_${name}_") }
            .map { it.name.removePrefix("dyn_${name}_") }
        trace(NodeType.SYSTEM_EVENT, "mounted: $name tools=$tools deps=${manifest.deps}")
        return FunctionResult.success(
            "✅ 模块「$name」已挂载（${tools.size} 个工具）。立即调用：" +
                tools.joinToString("、") { "dyn_${name}_$it" } +
                if (manifest.configSchema != "[]") "；配置已可经模块配置页修改（保存即重启生效）" else "")
    }

    private suspend fun handleList(): FunctionResult {
        val rows = try { dao.observeAll().first() } catch (_: Exception) { emptyList() }
        if (rows.isEmpty()) return FunctionResult.success("（暂无动态模块——develop_module 可创建）")
        val sb = StringBuilder()
        rows.forEach { m ->
            val toolCount = toolCatalog.getAllDefinitions().count { it.name.startsWith("dyn_${m.id}_") }
            sb.append("[${m.id}] ${stateIcon(m.state)} ${m.origin} 工具=$toolCount deps=${m.depsList().joinToString(",").ifBlank { "无" }}\n")
        }
        return FunctionResult.success(sb.toString())
    }

    /** 启停（P1-6：enable=探活重试语义；mount/unmount 内含 supervisor 起停 + FC 注册注销 + DB 状态） */
    private suspend fun handleToggle(args: Map<String, Any?>, enable: Boolean): FunctionResult {
        val id = args["id"] as? String ?: return FunctionResult.failure("id 必填")
        val entity = dao.getById(id)
            ?: return FunctionResult.failure("模块「$id」不存在（list_modules 查看清单）")
        val ok = if (enable) mcpClient.mountDynamicModule(entity)
        else mcpClient.unmountDynamicModule(entity)
        trace(NodeType.SYSTEM_EVENT, "toggle: $id enable=$enable ok=$ok")
        return if (ok) FunctionResult.success("✅ 已${if (enable) "启用" else "停用"} $id")
        else FunctionResult.failure("${if (enable) "启用失败（探活/挂载未过——见 mcp_client 日志）" else "停用失败"}：$id")
    }

    private fun stateIcon(state: String) = when (state) {
        "ACTIVE" -> "🟢"; "CRASHED" -> "🔴"; else -> "⚪"
    }

    companion object {
        private const val TRACE_ID = "code_dev"

        /** 《模块构建指引》精简规范（FC description 内嵌——引导 LLM 产出规范模块） */
        private const val DEVELOP_GUIDE =
            "开发/更新 Termux Debian 工作站动态模块（Python），通过即自动挂载、工具零弹窗直用。" +
                "code 规范：① 头部可选单行 manifest：# ZERO_MANIFEST: {\"deps\":[pip包],\"termuxDeps\":[],\"configSchema\":[{\"key\",\"label\",\"type\":\"text|number|toggle\",\"default\",(\"min\",\"max\"),\"description\"}]}；" +
                "② 用 zero_tool shim（已就位）：@tool 装饰器（函数类型注解 str/int/float/bool + docstring 即工具描述与参数 schema）、" +
                "结尾 serve() 起服务（勿阻塞其前——网络等待/长计算放工具函数内）、配置经 get_config() 读勿硬编码、大产物用 serve_file 注册后告知用户路径；" +
                "③ import 自由；pip deps 必须为包名==精确版本，在独立 venv 安装；系统依赖先在工作站准备；" +
                "④ 分钟级以上长任务必须拆 start_job→job_id + poll_status 两工具（勿同步等待）；" +
                "⑤ 同名重调=更新（旧进程停→新起）。语法/依赖/试跑失败返回 stderr——按行号修正后重调。" +
                "⚠️ 本工具族（develop/list/enable/disable_module）供对话主循环使用，自动化任务中勿递归调用。"
    }
}

/**
 * 代码开发配置（§9.1 CodeDevModule 三字段）。
 * repairMaxRounds 由 M3.3 修复回路消费；probeTimeoutS 透传 CompileManager.probe（P1-7）；
 * maxModules=DynamicModuleStore 总数上限（P1-8 installed/active 不区分）。
 */
class CodeDevModuleConfig : ModuleConfigBase() {

    @ConfigField(label = "修复轮上限", fieldType = ConfigFieldType.NUMBER,
        description = "develop_module 失败自动修复轮数上限（M3.3 消费——token 成本相关）", min = 1.0, max = 5.0)
    var repairMaxRounds: Int = 3

    @ConfigField(label = "探活超时(秒)", fieldType = ConfigFieldType.NUMBER,
        description = "模块试跑「进程启动→端点回报」超时（不含 pip 安装——P1-7）", min = 5.0, max = 120.0)
    var probeTimeoutS: Int = 15

    @ConfigField(label = "模块数上限", fieldType = ConfigFieldType.NUMBER,
        description = "动态模块总数上限（超出拒开发——先清理不再使用的）", min = 5.0, max = 200.0)
    var maxModules: Int = 50

    override fun toJson(): String = JSONObject().apply {
        put("repairMaxRounds", repairMaxRounds)
        put("probeTimeoutS", probeTimeoutS)
        put("maxModules", maxModules)
    }.toString()

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            repairMaxRounds = obj.optInt("repairMaxRounds", 3).coerceIn(1, 5)
            probeTimeoutS = obj.optInt("probeTimeoutS", 15).coerceIn(5, 120)
            maxModules = obj.optInt("maxModules", 50).coerceIn(5, 200)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = CodeDevModuleConfig()
}
