package com.zero.v2.services.mcp

import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.data.dao.DynamicModuleDao
import com.zero.v2.core.data.entity.DynamicModuleEntity
import com.zero.v2.core.data.store.ModuleConfigStore
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
import com.zero.v2.services.security.CredentialStore
import com.zero.v2.services.termux.TermuxBridge
import com.zero.v2.services.termux.ReleaseManager
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val START_READ_TIMEOUT_S = 130L
private const val START_CALL_TIMEOUT_S = 140L

/**
 * MCP 客户端模块 — Step 8.3（吸收 McpTestModule，正式模块化）。
 *
 * 职责：
 *   - 服务器生命周期：从 McpServerStore 读取启用的服务器 → 创建 McpClient → 发现工具
 *   - 工具动态注册：MCP tools → FunctionDefinition（JSON Schema 透传 parameters Map）
 *     → FunctionRegistryModule 注册为 `mcp_<server>_<tool>`
 *   - 权限（D9/D24）：服务器级 autoPermission 配置 → 全 AUTO 或全 CONFIRM
 *   - 管理 FC：mcp_add_server / mcp_remove_server / mcp_refresh_tools / mcp_test_connection
 *
 * onAwake 时后台发现（网络不可用优雅跳过）；D51 启动恢复 server 配置。
 */
@Singleton
class McpClientModule
    @Inject
    // MCP lifecycle coordinates independent store, registry, credentials, trust, releases and tracing owners.
    @Suppress("LongParameterList")
    constructor(
        private val serverStore: McpServerStore,
        private val functionRegistry: com.zero.v2.core.moduleapi.ToolRegistrar,
        private val secureKeyStore: CredentialStore,
        private val traceManager: AgentTraceManager,
        private val trustConfig: TrustConfig, // S7.2：启动实例化锚（collect store 流自刷新 pins）
        private val releaseManager: ReleaseManager,
        private val dynamicModuleDao: DynamicModuleDao, // M2.2：动态模块注册表（D51/状态）
        private val resultDecoder: McpResultDecoder,
    ) : IZeroModule, com.zero.v2.core.moduleapi.ToolContributor {
        private val termuxBridge get() = releaseManager.termuxBridge
        private val moduleConfigStore get() = releaseManager.configStore
        override val metadata =
            ModuleMetadata(
                id = "mcp_client",
                displayName = "MCP 客户端",
                description = "连接 MCP 服务器并自动注册工具（Streamable HTTP）",
                category = ModuleCategory.INSTRUMENT,
            )

        private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
        override val lifecycle = _lifecycle.asStateFlow()
        override var isEnabled: Boolean = true
        override val requiredPermissions: List<String> = emptyList()
        override val config = McpClientModuleConfig() // §9.1（dyn 调用域：moduleCallTimeoutS）

        /** 已发现的工具（serverToolName → 所属服务器 ID） */
        private data class RegisteredTool(
            val fcName: String, // mcp_<server>_<tool>
            val serverId: String,
            val serverName: String,
            val mcpToolName: String, // MCP 原始工具名
            val description: String,
        )

        private val registeredTools = ConcurrentHashMap<String, RegisteredTool>()
        private val activeClients = ConcurrentHashMap<String, McpClient>()
        // Supervisor start replaces the process and endpoint. Keep each module's entire
        // start/initialize/register/state handoff ordered with its explicit stop.
        private val dynamicMountLocks = ConcurrentHashMap<String, Mutex>()
        private val _discoveredCount = MutableStateFlow(0)
        val discoveredCount = _discoveredCount.asStateFlow()

        override suspend fun onAwake() {
            _lifecycle.value = ModuleLifecycle.AWAKE
            trustConfig.refresh() // S7.2：启动即应用 pins（collect 流的自刷新兜底）

            refreshAllTools()
            // M2.2：D51 启动恢复（P0-4——非 CRASHED 经 supervisor 重启→重新发现端点→mount，
            // 不直连 DB lastEndpoint）+ 低频 CRASHED 守卫
            dynScope.launch { restoreDynamicModules() }
            dynScope.launch { dynCrashWatchdog() }
        }

        override suspend fun onStart() {
            _lifecycle.value = ModuleLifecycle.STARTED
        }

        override suspend fun onDestroy() {
            trustConfig.close()
            _lifecycle.value = ModuleLifecycle.DESTROYED
            dynScope.cancel()
            dynEntries.values.forEach { it.fcNames.forEach { fc -> functionRegistry.unregister(fc) } }
            dynEntries.clear()
            clearAllTools()
        }

        override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {
            traceManager.record(
                "mcp_client",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "config_changed: moduleCallTimeoutS=${(config as McpClientModuleConfig).moduleCallTimeoutS}s",
            )
        }

        override suspend fun checkAvailability(): Boolean = true

        override fun markDisabled() {
            _lifecycle.value = ModuleLifecycle.DISABLED
        }

        // ═══ 管理 FC（自身工具，供 LLM 和用户管理 MCP 生态）═══

        override fun contributions(): List<com.zero.v2.core.moduleapi.ToolContribution> =
            com.zero.v2.core.moduleapi.toolDeclarations(metadata.id) {
            val functionRegistry = this
            // 测试连接（吸收自 McpTestModule）
            functionRegistry.register(
                FunctionDefinition(
                    name = "mcp_test_connection",
                    description = "验证 MCP 服务器连接：initialize + tools/list，返回工具清单。用于诊断连接是否正常。",
                    parameters =
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "url" to mapOf("type" to "string", "description" to "MCP Streamable HTTP 端点"),
                                    "api_key" to mapOf("type" to "string", "description" to "API Key（可选）"),
                                ),
                            "required" to listOf("url"),
                        ),
                ),
                metadata.id,
                permission = ActionPermission.AUTO,
            ) { args ->
                val url = args["url"] as? String ?: ""
                val apiKey = args["api_key"] as? String
                testConnection(url, apiKey)
            }

            functionRegistry.register(
                FunctionDefinition(
                    name = "mcp_add_server",
                    description = "添加 MCP 服务器（连接后自动发现工具并注册）。需要用户提供端点 URL 和可选的 API Key。",
                    parameters =
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "name" to mapOf("type" to "string", "description" to "服务器显示名（如 高德地图）"),
                                    "url" to mapOf("type" to "string", "description" to "Streamable HTTP 端点"),
                                    "api_key" to mapOf("type" to "string", "description" to "API Key（可选）"),
                                ),
                            "required" to listOf("name", "url"),
                        ),
                ),
                metadata.id,
                permission = ActionPermission.CONFIRM,
            ) { args ->
                val name = args["name"] as? String ?: ""
                val url = args["url"] as? String ?: ""
                val apiKey = args["api_key"] as? String
                addServer(name, url, apiKey)
            }

            functionRegistry.register(
                FunctionDefinition(
                    name = "mcp_remove_server",
                    description = "删除已注册的 MCP 服务器（其工具同时注销）。",
                    parameters =
                        mapOf(
                            "type" to "object",
                            "properties" to
                                mapOf(
                                    "name" to mapOf("type" to "string", "description" to "服务器显示名"),
                                ),
                            "required" to listOf("name"),
                        ),
                ),
                metadata.id,
                permission = ActionPermission.CONFIRM,
            ) { args ->
                val name = args["name"] as? String ?: ""
                removeServer(name)
            }

            functionRegistry.register(
                FunctionDefinition(
                    name = "mcp_refresh_tools",
                    description = "重新发现所有已启用 MCP 服务器的工具（服务器配置变更后调用）。",
                    parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                ),
                metadata.id,
                permission = ActionPermission.AUTO,
            ) { refreshAllToolsAsResult() }

            functionRegistry.register(
                FunctionDefinition(
                    name = "mcp_list_servers",
                    description = "列出所有已配置的 MCP 服务器及其状态和工具数量。",
                    parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                ),
                metadata.id,
                permission = ActionPermission.AUTO,
            ) { listServers() }
        }

        // ═══ 工具发现与注册 ═══

        /**
         * 重新发现所有启用服务器的工具（清旧注册 + 注册新工具）。
         * 网络不可用 → 优雅跳过（trace 提示）。
         */
        private val refreshMutex = Mutex()
        private data class ConfirmedConnection(val server: McpServerConfig, val version: Long)
        private data class DiscoveredConnection(val server: McpServerConfig, val client: McpClient,
            val tools: List<McpClient.McpTool>, val version: Long)
        private val confirmedConnections = ConcurrentHashMap<String, ConfirmedConnection>()

        suspend fun refreshAllTools(): McpRefreshReport = withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                val snapshot = serverStore.snapshot()
                val discovered = mutableListOf<DiscoveredConnection>()
                val failed = mutableListOf<String>()
                val empty = mutableListOf<String>()
                try {
                    for (server in snapshot.servers) {
                        val candidate = discoverConnection(server, snapshot.versions[server.id] ?: 0L)
                        kotlin.coroutines.coroutineContext.ensureActive()
                        if (candidate == null) failed += server.id else {
                            discovered += candidate
                            if (candidate.tools.isEmpty()) empty += server.id
                        }
                    }
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val published = serverStore.publishIfCurrent(snapshot) {
                        pruneOutdatedConnections()
                        discovered.forEach { publishConnection(it) }
                        updateDiscoveredCount()
                    }
                    McpRefreshReport(registeredTools.size, failed, empty, stale = !published)
                } finally {
                    // Cancellation keeps confirmed unchanged connections, but never permits deleted/changed owners.
                    serverStore.publishIfCurrent(serverStore.snapshot()) {
                        pruneOutdatedConnections()
                        updateDiscoveredCount()
                    }
                }
            }
        }

        private suspend fun discoverConnection(server: McpServerConfig, version: Long): DiscoveredConnection? {
            val apiKey = if (server.apiKeyRef.isNotBlank())
                secureKeyStore.getOrFallbackString(server.apiKeyRef, null) ?: server.apiKeyRef else null
            val client = McpClient(server.name, McpEndpoint.forTransport(server, apiKey),
                apiKey, traceManager, resultDecoder)
            val result = client.discoverTools()
            kotlin.coroutines.coroutineContext.ensureActive()
            return if (result.failed) null else DiscoveredConnection(server, client, result.tools, version)
        }

        private fun publishConnection(candidate: DiscoveredConnection) {
            val server = candidate.server
            val newNames = candidate.tools.map { "mcp_${server.id}_${it.name}" }.toSet()
            registeredTools.values.filter { it.serverId == server.id && it.fcName !in newNames }.forEach {
                functionRegistry.unregister(it.fcName)
                registeredTools.remove(it.fcName)
            }
            val permission = if (server.autoPermission) ActionPermission.AUTO else ActionPermission.CONFIRM
            candidate.tools.forEach { tool ->
                val fcName = "mcp_${server.id}_${tool.name}"
                functionRegistry.register(FunctionDefinition(name = fcName,
                    description = "[${server.name}] ${tool.description}",
                    parameters = jsonSchemaToMap(tool.inputSchema)),
                    metadata.id, permission) { args -> callTool(server.id, tool.name, args) }
                registeredTools[fcName] = RegisteredTool(fcName, server.id, server.name, tool.name, tool.description)
            }
            activeClients[server.id] = candidate.client
            confirmedConnections[server.id] = ConfirmedConnection(server, candidate.version)
        }

        private fun pruneOutdatedConnections() {
            confirmedConnections.entries.filter { !serverStore.isCurrent(it.value.server, it.value.version) }
                .forEach { clearServerTools(it.key) }
        }

        private fun updateDiscoveredCount() {
            _discoveredCount.value = registeredTools.size + dynEntries.values.sumOf { it.fcNames.size }
        }

        /** 清除所有已注册的 MCP 动态工具 */
        private fun clearAllTools() {
            registeredTools.keys.forEach { functionRegistry.unregister(it) }
            registeredTools.clear()
            confirmedConnections.clear()
            activeClients.clear()
            _discoveredCount.value = 0
        }

        /** 单服务器工具清除（删除服务器时用） */
        private fun clearServerTools(serverId: String) {
            // FunctionRegistry 侧注销
            val toRemove = registeredTools.keys.filter { registeredTools[it]?.serverId == serverId }
            toRemove.forEach { functionRegistry.unregister(it) }
            registeredTools.entries.removeAll { it.value.serverId == serverId }
            activeClients.remove(serverId)
            confirmedConnections.remove(serverId)
        }

        // ═══ M2.2：动态模块热插拔（D64 注册表主体 · P0-4 runtime 端点 · D67 恒 AUTO）═══

        private data class DynEntry(
            val client: McpClient,
            val fcNames: List<String>,
            val endpoint: String,
        )

        private val dynEntries = ConcurrentHashMap<String, DynEntry>() // moduleId → 已挂载
        private val dynScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val supHttp =
            OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain -> chain.proceed(termuxBridge.authorizeSupervisorRequest(chain.request())) }
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        // Runtime probes may take up to 120s plus process-group termination.
        // Keep this start-only contract separate from ordinary control traffic.
        private val startHttp = supHttp.newBuilder()
            .readTimeout(START_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(START_CALL_TIMEOUT_S, TimeUnit.SECONDS).build()

        /** supervisor 控制面 URL 前缀（固定端口，TermuxBridge 域） */
        private fun supBase() = "http://127.0.0.1:${termuxBridge.supervisorPort}"

        private fun supGet(path: String): JSONObject? =
            try {
                supHttp.newCall(Request.Builder().url(supBase() + path).build())
                    .execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: "null") else null }
            } catch (_: Exception) {
                null
            }

        private fun supPost(
            path: String,
            body: JSONObject,
        ): JSONObject? =
            try {
                supHttp.newCall(
                    termuxBridge.authorizeSupervisorRequest(Request.Builder().url(supBase() + path)
                        .post(body.toString().toRequestBody(null)).build()),
                )
                    .execute().use { r -> if (r.isSuccessful) JSONObject(r.body?.string() ?: "null") else null }
            } catch (_: Exception) {
                null
            }

        private suspend fun setState(
            module: DynamicModuleEntity,
            state: String,
            endpoint: String? = null,
            crashCount: Int? = null,
        ) {
            dynamicModuleDao.upsert(
                module.copy(
                    state = state,
                    lastEndpoint = endpoint ?: module.lastEndpoint, // 仅调试（P0-4）
                    lastCrashAt = if (state == "CRASHED") System.currentTimeMillis() else module.lastCrashAt,
                    crashCount = crashCount ?: module.crashCount,
                ),
            )
        }

        /**
         * 挂载动态模块（M3.1 CompileManager 通过后 / M2.4 UI enable 调用）：
         * supervisor start（**端点以 runtime 回报为准——P0-4**）→ McpClient 连 → tools/list
         * → 注册 dyn_<id>_<tool>（**perm 恒 AUTO，D67**）→ DB state=ACTIVE。
         */
        suspend fun mountDynamicModule(module: DynamicModuleEntity, reuseActive: Boolean = false): Boolean =
            dynamicMountLocks.getOrPut(module.id) { Mutex() }.withLock { withContext(Dispatchers.IO) {
                if (termuxBridge.isEmergencyStop) return@withContext false
                if (!ensureModuleSupervisor(module)) return@withContext false
                // D68：Zero 侧配置经 ModuleConfigStore 注入（env ZERO_MOD_CONFIG 由 supervisor 透传）
                val configJson = moduleConfigStore.get(module.id)
                // 传输选择（D59，M2.3）：模块声明优先（entity.transport 缺省 http）；
                // HTTP 端点连不通 → 运行时探测切 socket（loopback 受限兜底，B13 场景）
                var transport = module.transport.ifBlank { "http" }
                var start = startDynamicModuleWithRetry(
                    request = {
                        confirmedModuleStart(startHttp, supBase() + "/module/${module.id}/start",
                            moduleStartBody(module.id, transport, configJson).put("reuse_active", reuseActive))
                    },
                    emergencyStopped = { termuxBridge.isEmergencyStop },
                )
                var endpoint = start?.optString("endpoint").orEmpty()
                if (start?.optBoolean("ok") != true || endpoint.isBlank()) {
                    traceManager.record(
                        "mcp_client",
                        NodeType.ERROR,
                        metadata.id,
                        "dyn_mount_start_failed: ${module.id} transport=$transport resp=${start?.toString()?.take(120)}",
                    )
                    if (start?.optBoolean("uncertain") == true) {
                        traceManager.record("mcp_client", NodeType.ERROR, metadata.id,
                            "dyn_mount_start_unconfirmed: ${module.id}; keep prior intent, no late publication")
                    } else setState(module, "DISABLED")
                    return@withContext false
                }
                val clientKey = "dyn_${module.id}"
                var pair = buildAndInit(clientKey, endpoint)
                if (pair == null && transport == "http") {
                    // D59 运行时探测：loopback 连不通（握手失败）→ 切 socket 重挂
                    // （同名 start=supervisor 先停旧组——http 形态模块重启为 serve_socket 形态）
                    traceManager.record(
                        "module_transport",
                        NodeType.SYSTEM_EVENT,
                        metadata.id,
                        "path=loopback_failed moduleId=${module.id} endpoint=$endpoint → 切 unix-socket 兜底（D59 运行时探测）",
                    )
                    transport = "socket"
                    start = startDynamicModuleWithRetry(
                        request = {
                            confirmedModuleStart(startHttp, supBase() + "/module/${module.id}/start",
                                moduleStartBody(module.id, transport, configJson))
                        },
                        emergencyStopped = { termuxBridge.isEmergencyStop },
                    )
                    endpoint = start?.optString("endpoint").orEmpty()
                    if (start?.optBoolean("uncertain") == true) return@withContext false
                    pair = if (start?.optBoolean("ok") == true) buildAndInit(clientKey, endpoint) else null
                }
                val client =
                    pair ?: run {
                        traceManager.record(
                            "mcp_client", NodeType.ERROR, metadata.id,
                            "dyn_mount_init_failed: ${module.id} transport=$transport endpoint=$endpoint",
                        )
                        supPost("/module/${module.id}/stop", JSONObject())
                        setState(module, "DISABLED")
                        return@withContext false
                    }
                finishDynamicMount(module, client, endpoint)
            } }

        private suspend fun ensureModuleSupervisor(module: DynamicModuleEntity): Boolean {
            val ready = termuxBridge.ensureSupervisor()
            if (!ready) traceManager.record("mcp_client", NodeType.ERROR, metadata.id,
                "dyn_mount_failed: ${module.id} supervisor 不可用")
            return ready
        }

        private suspend fun finishDynamicMount(module: DynamicModuleEntity, client: McpClient,
            endpoint: String): Boolean {
            val clientKey = "dyn_${module.id}"
            val tools = client.toolsList()
            if (tools.isEmpty()) {
                traceManager.record(
                    "mcp_client",
                    NodeType.ERROR,
                    metadata.id,
                    "dyn_mount_no_tools: ${module.id} endpoint=$endpoint",
                )
                supPost("/module/${module.id}/stop", JSONObject())
                setState(module, "DISABLED")
                return false
            }
            unmountFcs(module.id) // 同名重挂=更新（旧 FC 先清）
            activeClients[clientKey] = client
            val fcNames =
                tools.map { tool ->
                    val fcName = "dyn_${module.id}_${tool.name}"
                    functionRegistry.register(
                        FunctionDefinition(
                            name = fcName,
                            description = "[动态:${module.id}] ${tool.description}",
                            parameters = jsonSchemaToMap(tool.inputSchema),
                        ),
                        metadata.id,
                        permission = ActionPermission.AUTO, // D67：挂载即 AUTO
                    ) { args -> callDynTool(module.id, tool.name, args) }
                    fcName
                }
            dynEntries[module.id] = DynEntry(client, fcNames, endpoint)
            // M4.1：告警后再启用=计数清零（A-II-3——人工介入后重新计数；低于 K 的历史崩不清，跨会话累计）
            setState(
                module,
                "ACTIVE",
                endpoint,
                crashCount = if (module.crashCount >= termuxBridge.crashAutoDisableK) 0 else null,
            )
            _discoveredCount.value = registeredTools.size + dynEntries.values.sumOf { it.fcNames.size }
            traceManager.record(
                "mcp_client",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "dyn_mounted: ${module.id} tools=${tools.size} endpoint=$endpoint（runtime 回报）",
            )
            return true
        }

        /**
         * /module/<id>/start body（D68 config 注入 + D59 transport；socket 需抽象名供 supervisor 设 env）。
         * config 字符串在边界归一化为 JSONObject——env ZERO_MOD_CONFIG 必须是 JSON 对象
         * （String 透传会被 supervisor 侧 json.dumps 二次包裹，模块 get_config() 拿到 str 非 dict）。
         */
        private fun moduleStartBody(
            moduleId: String,
            transport: String,
            configJson: String?,
        ): JSONObject {
            val cfg =
                try {
                    if (configJson.isNullOrBlank()) JSONObject() else JSONObject(configJson)
                } catch (_: Exception) {
                    JSONObject()
                }
            return JSONObject().put("transport", transport).put("config", cfg).apply {
                if (transport == "socket") put("socket_name", McpSocketTransport.moduleAbstractName(moduleId))
            }
        }

        /**
         * 端点 → McpClient（P0-4 runtime 回报端点）：unix:// = AF_UNIX 兜底
         * （McpSocketTransport 注入构造，M2.3）；http(s):// = 既有 url 构造；其余形态 null。
         * module_transport 埋点（§6.2 清单）。
         */
        private fun buildDynClient(
            clientKey: String,
            endpoint: String,
        ): McpClient? {
            if (endpoint.startsWith("unix://")) {
                val name = endpoint.removePrefix("unix://")
                traceManager.record(
                    "module_transport",
                    NodeType.SYSTEM_EVENT,
                    metadata.id,
                    "path=socket name=\\0$name（AF_UNIX 抽象名兜底，D59）",
                )
                return McpClient(clientKey, McpSupervisorSocketTransport(
                    clientKey.removePrefix("dyn_"), endpoint, termuxBridge.supervisorPort,
                    termuxBridge::authorizeSupervisorRequest,
                ), traceManager, resultDecoder)
            }
            if (endpoint.startsWith("http")) {
                traceManager.record("module_transport", NodeType.SYSTEM_EVENT, metadata.id, "path=http $endpoint")
                return McpClient(clientKey, endpoint, null, traceManager, resultDecoder)
            }
            traceManager.record("mcp_client", NodeType.ERROR, metadata.id, "dyn_mount_bad_endpoint: $endpoint")
            return null
        }

        /** 建客户端并 initialize（端点形态非法或握手失败 → null——调用方决定是否走 D59 socket 兜底）。 */
        private suspend fun buildAndInit(
            clientKey: String,
            endpoint: String,
        ): McpClient? {
            val c = buildDynClient(clientKey, endpoint) ?: return null
            return if (c.initialize()) c else null
        }

        /** 卸载动态模块（UI disable / 更名重挂前）：supervisor stop → FC 注销 → DB DISABLED。 */
        suspend fun unmountDynamicModule(module: DynamicModuleEntity): Boolean =
            dynamicMountLocks.getOrPut(module.id) { Mutex() }.withLock { withContext(Dispatchers.IO) {
                val stopped = confirmDynamicModuleStop(supPost("/module/${module.id}/stop", JSONObject())) {
                    unmountFcs(module.id)
                    setState(module, "DISABLED")
                    traceManager.record("mcp_client", NodeType.SYSTEM_EVENT, metadata.id, "dyn_unmounted: ${module.id}")
                }
                if (!stopped) {
                    traceManager.record("mcp_client", NodeType.ERROR, metadata.id,
                        "dyn_stop_unconfirmed: ${module.id} 保留工具注册与原运行状态")
                }
                stopped
            } }

        private fun unmountFcs(moduleId: String) {
            dynEntries.remove(moduleId)?.let {
                it.fcNames.forEach { fc -> functionRegistry.unregister(fc) }
                activeClients.remove("dyn_$moduleId")
            }
            _discoveredCount.value = registeredTools.size + dynEntries.values.sumOf { it.fcNames.size }
        }

        /** dyn FC 调用（moduleCallTimeoutS 生效——§9.1；M0 timeoutMs 参数消费位）。 */
        private suspend fun callDynTool(
            moduleId: String,
            toolName: String,
            args: Map<String, Any?>,
        ): FunctionResult =
            releaseManager.operationsMutex.withLock { withContext(Dispatchers.IO) {
                if (termuxBridge.isEmergencyStop) return@withContext FunctionResult.failure("动态模块紧急停止已开启")
                val gate = releaseManager.observe(moduleId)
                if (!gate.ok) {
                    unmountFcs(moduleId)
                    return@withContext FunctionResult.failure(gate.error ?: "部署状态无法验证")
                }
                val entry =
                    dynEntries[moduleId]
                        ?: return@withContext FunctionResult.failure("动态模块 $moduleId 未挂载（可能已崩溃停用——见动态模块页）")
                val timeoutMs = (config as McpClientModuleConfig).moduleCallTimeoutS * 1000L
                val result = entry.client.toolsCall(toolName, args, timeoutMs)
                val failed = !result.success
                val observed = releaseManager.observe(moduleId, !failed)
                if (!observed.ok) {
                    unmountFcs(moduleId)
                    dynamicModuleDao.getById(moduleId)?.let { mountDynamicModule(it) }
                }
                result
            } }

        /** D51 启动恢复：非 CRASHED（state=ACTIVE）模块经 supervisor 重新发现端点后重挂（P0-4——不信任 DB 旧端点）。 */
        private suspend fun restoreDynamicModules() {
            val actives =
                try {
                    dynamicModuleDao.getEnabled()
                } catch (e: Exception) {
                    emptyList()
                }
            if (actives.isEmpty()) return
            traceManager.record(
                "mcp_client",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "dyn_restore_start: count=${actives.size} [${actives.joinToString { it.id }}]",
            )
            for (m in actives) {
                // Reattach a matching healthy generation after host restore.
                val ok = mountDynamicModule(m, reuseActive = true)
                if (!ok) {
                    traceManager.record(
                        "mcp_client",
                        NodeType.ERROR,
                        metadata.id,
                        "dyn_restore_failed: ${m.id} state=${dynamicModuleDao.getById(m.id)?.state}（挂载未完成，请查询运行记录）",
                    )
                }
            }
        }

        /**
         * 低频 CRASHED 守卫（120s，A-II-3 全局层的 Zero 侧观测）：supervisor /health 的 modules[]
         * 反映 runtime 状态（就地重启超限→CRASHED）→ 注销 FC + DB CRASHED + trace 告警。
         */
        private suspend fun dynCrashWatchdog() {
            while (true) {
                delay(120_000L)
                if (dynEntries.isEmpty()) continue
                val health = termuxBridge.health()?.raw ?: continue
                // Recovery snapshots are partial, not an authoritative crash/disable decision.
                if (!com.zero.v2.services.termux.SupervisorBundle.isReady(health)) continue
                val states = health.optJSONArray("modules") ?: continue
                val svState = HashMap<String, String>()
                for (i in 0 until states.length()) {
                    val m = states.optJSONObject(i) ?: continue
                    svState[m.optString("id")] = m.optString("state")
                }
                for ((id, _) in dynEntries) {
                    when (svState[id]) {
                        "CRASHED" -> {
                            unmountFcs(id)
                            // M4.1（A-II-3 全局层）：跨会话崩数计 K——超限 auto-DISABLE + 告警行
                            // （低于 K 保持 CRASHED：用户可再启用 P1-6；计数跨会话累计，
                            //  仅告警后的人工再启用清零——见 mountDynamicModule）
                            val k = termuxBridge.crashAutoDisableK
                            dynamicModuleDao.getById(id)?.let { m ->
                                val count = m.crashCount + 1
                                if (count >= k) {
                                    setState(m, "DISABLED", crashCount = count)
                                    traceManager.record(
                                        "mcp_client",
                                        NodeType.ERROR,
                                        metadata.id,
                                        "dyn_auto_disabled_alert: $id 跨会话崩数 $count≥K=$k——自动停用（权限中心告警；再启用=重新计数）",
                                    )
                                } else {
                                    setState(m, "CRASHED", crashCount = count)
                                    traceManager.record(
                                        "mcp_client",
                                        NodeType.ERROR,
                                        metadata.id,
                                        "dyn_crashed_alert: $id 崩溃超限自动停（计数 $count/K=$k；用户可再启用→PROBED→ACTIVE，P1-6）",
                                    )
                                }
                            }
                        }
                        "", "STOPPED" -> {
                            unmountFcs(id)
                            dynamicModuleDao.getById(id)?.let { setState(it, "DISABLED") }
                            traceManager.record(
                                "mcp_client",
                                NodeType.SYSTEM_EVENT,
                                metadata.id,
                                "dyn_stopped_external: $id supervisor 侧已停——FC 注销",
                            )
                        }
                    }
                }
            }
        }

        /** MCP 工具调用 handler */
        private suspend fun callTool(
            serverId: String,
            toolName: String,
            args: Map<String, Any?>,
        ): FunctionResult =
            withContext(Dispatchers.IO) {
                val confirmed = confirmedConnections[serverId]
                if (confirmed == null || !serverStore.isCurrent(confirmed.server, confirmed.version)) {
                    return@withContext FunctionResult.failure("连接已变更，请刷新工具（mcp_refresh_tools）")
                }
                val client =
                    activeClients[serverId]
                        ?: return@withContext FunctionResult.failure("MCP 服务器未连接，请刷新工具（mcp_refresh_tools）")
                val result = client.toolsCall(toolName, args)
                if (!result.success) return@withContext result
                val data = rewriteAmapUri(result.data)
                maybeNotifyAsyncTask(toolName, data)
                result.copy(data = data)
            }

        /**
         * C4.3''：按服务器名直调 MCP 工具（TriggerHub 云源轮询消费；服务器名 D103 固定）。
         * 返回 content[0].text 解包后的 success.result JSONObject；失败/解析失败 = null（调用方静默下轮再试）。
         * E28：activeClients 键为 server.id（如 svr_mtk7pe06）而非显示名——必须先 name→id 解析。
         */
        suspend fun callToolForServer(
            serverName: String,
            toolName: String,
            args: Map<String, Any?>,
        ): JSONObject? =
            withContext(Dispatchers.IO) {
                val serverId =
                    registeredTools.values.firstOrNull { it.serverName == serverName }?.serverId
                        ?: serverStore.enabledServers.firstOrNull { it.name == serverName }?.id
                        ?: return@withContext null
                val fr = callTool(serverId, toolName, args)
                val text =
                    (fr.data as? String)?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: return@withContext null
                try {
                    val obj = JSONObject(text)
                    if (obj.optBoolean("success")) obj.optJSONObject("result") else null
                } catch (_: Exception) {
                    null
                }
            }

        /**
         * C4.2'：异步云任务提交回调——任何 MCP 调用成功响应含 task_id 时触发
         * （TriggerHub 登记后轮询 cloud_task_status 感知完成）。解耦：未设回调时零开销。
         * E29：签名含 toolName——调用方须过滤自产工具（wechat_read 的任务不得进感知集，
         * 否则自激励风暴打爆 TriggerCap）。
         */
        @Volatile var onAsyncTaskSubmitted: ((taskId: String, toolName: String) -> Unit)? = null

        private fun maybeNotifyAsyncTask(
            toolName: String,
            resultText: String,
        ) {
            val cb = onAsyncTaskSubmitted ?: return
            val tid =
                try {
                    val o = JSONObject(resultText.trim())
                    if (o.optBoolean("success")) o.optJSONObject("result")?.optString("task_id") else null
                } catch (_: Exception) {
                    null
                } ?: return
            if (tid.isNotBlank()) cb(tid, toolName)
        }

        /**
         * C4.4'' 修订：MCP 工具的 FC 注册真名（`mcp_{serverId}_{tool}`）——
         * Prompt 引导必须用真名（裸名 wechat_send 会导致 LLM 调用不存在的工具，回复沉入会话）。
         * @return 注册名；服务器/工具未注册时 null（调用方回退裸名）
         */
        fun fcNameForTool(
            serverName: String,
            toolName: String,
        ): String? =
            registeredTools.values.firstOrNull {
                it.serverName == serverName && it.mcpToolName == toolName
            }?.fcName

        /**
         * E1.2-解耦（2026-09-20 用户裁定 a+b）：按能力关键词解析 FC 真名——云服工具改名后
         * 手机 prompt 无需发版（精确名失配时按能力模式在注册表内模糊匹配）。
         * @return 注册名；无匹配时 null（调用方回退既定候选裸名）
         */
        fun fcNameByCapability(
            serverName: String,
            capability: Regex,
        ): String? =
            registeredTools.values.firstOrNull {
                it.serverName == serverName && capability.containsMatchIn(it.mcpToolName)
            }?.fcName

        /**
         * N4.4 + 实测修复（08-31 18:xx）：高德 amapuri:// 深链改写。
         *
         * 通用改写 amapuri://x → https://uri.amap.com/x 仅能唤起 App 首页（schema_navi 输出
         * 不含 URI API 规范的导航意图）。实测可用官方格式（用户验证）：
         *   https://uri.amap.com/navigation?to=lon,lat&name=目的地&callnative=1 → 直接进入导航。
         * 故：能从 amapuri 链接提取到坐标（lat/lon 或纬度,经度对）与名称时，升级为官方导航 URL；
         * 提取不到则回退通用改写（至少可点开 App）。
         */
        private fun rewriteAmapUri(result: String): String {
            if (!result.contains("amapuri://")) return result
            val upgraded =
                Regex("amapuri://[^\\s\"'<>）)]+").replace(result) { m ->
                    toOfficialAmapNav(m.value) ?: "https://uri.amap.com/" + m.value.removePrefix("amapuri://")
                }
            traceManager.record(
                "mcp_proto",
                NodeType.SYSTEM_EVENT,
                metadata.id,
                "mcp_amapuri_rewritten: n=${result.split("amapuri://").size - 1} upgraded=" +
                    upgraded.contains("uri.amap.com/navigation"),
            )
            return upgraded
        }

        /** amapuri://schema_navi?... → 官方导航 URL（提取坐标+名称；参数不全返回 null 走通用回退） */
        private fun toOfficialAmapNav(amapUri: String): String? =
            try {
                val q =
                    amapUri.substringAfter('?', "").split('&')
                        .mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 } }
                        .associate { (k, v) -> k to java.net.URLDecoder.decode(v, "UTF-8") }
                val lat = q["lat"] ?: q["latitude"] ?: q["y"]
                val lon = q["lon"] ?: q["lng"] ?: q["longitude"] ?: q["x"]
                if (lat.isNullOrBlank() || lon.isNullOrBlank()) {
                    null
                } else {
                    val name = q["poiname"] ?: q["name"] ?: q["dname"] ?: q["sourceName"] ?: q["keyword"] ?: ""
                    "https://uri.amap.com/navigation?to=$lon,$lat" +
                        (if (name.isNotBlank()) "&name=${java.net.URLEncoder.encode(name, "UTF-8")}" else "") +
                        "&callnative=1"
                }
            } catch (_: Exception) {
                null
            }

        // ═══ 管理 FC 实现 ═══

        private suspend fun testConnection(
            url: String,
            apiKey: String?,
        ): FunctionResult {
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                return FunctionResult.failure("URL 必须以 http:// 或 https:// 开头")
            }
            val endpoint = McpEndpoint.forTransport(McpServerConfig("test", "test", url), apiKey)
            val client = McpClient("test", endpoint, apiKey, traceManager, resultDecoder)
            val result = client.discoverTools()
            if (result.failed) return FunctionResult.failure("连接或工具发现失败，请检查地址、网络与凭据")
            val tools = result.tools
            if (tools.isEmpty()) return FunctionResult.failure("连接成功，但未提供工具")
            val sb = StringBuilder("✅ 连接成功（${tools.size} 个工具）：\n")
            tools.forEachIndexed { i, t -> sb.appendLine("[${i + 1}] ${t.name} — ${t.description.take(80)}") }
            return FunctionResult.success(sb.toString())
        }

        private suspend fun addServer(
            name: String,
            url: String,
            apiKey: String?,
        ): FunctionResult {
            if (name.isBlank() || url.isBlank()) return FunctionResult.failure("name 和 url 不能为空")

            // 先验证连接
            val test = testConnection(url, apiKey)
            if (test is FunctionResult && !test.success) {
                return FunctionResult.failure("连接验证失败：${(test as? FunctionResult)?.errorMessage ?: "未知"}")
            }

            // 存 Key 到 SecureKeyStore
            val id = McpCredentialReference.newConnectionId()
            try {
                withContext(Dispatchers.IO) {
                    serverStore.saveWithCredential(
                        McpServerConfig(id = id, name = name, url = url, enabled = true, autoPermission = false),
                        apiKey, secureKeyStore,
                    )
                }
            } catch (_: IllegalStateException) {
                return FunctionResult.failure("连接保存失败，请检查存储与凭据后重试")
            } catch (_: SecurityException) {
                return FunctionResult.failure("凭据保存被拒绝，请检查密钥存储后重试")
            }

            // 刷新工具
            val report = refreshAllTools()
            val toolCount = registeredTools.values.count { it.serverId == id }
            return if (report.stale || id in report.failedIds)
                FunctionResult.failure("连接已保存；${report.message()}")
                else FunctionResult.success("MCP 服务器「$name」已保存，发现 $toolCount 个工具")
        }

        private suspend fun removeServer(name: String): FunctionResult {
            val server =
                serverStore.servers.value.find { it.name == name }
                    ?: return FunctionResult.failure("服务器「$name」不存在")
            return try {
                withContext(Dispatchers.IO) { serverStore.remove(server.id) }
                clearServerTools(server.id)
                refreshAllTools()
                FunctionResult.success("✅ 服务器「$name」已删除")
            } catch (_: IllegalStateException) {
                FunctionResult.failure("连接删除未保存，请检查存储后重试")
            }
        }

        private suspend fun refreshAllToolsAsResult(): FunctionResult {
            val report = refreshAllTools()
            return if (report.successful) FunctionResult.success(report.message())
                else FunctionResult.failure(report.message())
        }

        private fun listServers(): FunctionResult {
            val servers = serverStore.servers.value
            if (servers.isEmpty()) return FunctionResult.success("（无已配置的 MCP 服务器）")
            val sb = StringBuilder()
            servers.forEach { s ->
                val toolCount = registeredTools.values.count { it.serverId == s.id }
                sb.appendLine(
                    "[${s.name}] ${if (s.enabled) "🟢" else "🔴"} " +
                        "url=${s.url.take(50)} tools=$toolCount " +
                        "perm=${if (s.autoPermission) "AUTO" else "CONFIRM"}",
                )
            }
            return FunctionResult.success(sb.toString())
        }

        // ═══ 工具函数 ═══

        /** JSONObject(JSON Schema) → Map<String, Any?>（递归转换，透传给 FunctionDefinition.parameters） */
        private fun jsonSchemaToMap(schema: JSONObject): Map<String, Any?> {
            return jsonToMap(schema)
        }

        private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
            val map = mutableMapOf<String, Any?>()
            for (key in obj.keys()) {
                map[key] =
                    when (val v = obj.get(key)) {
                        is JSONObject -> jsonToMap(v)
                        is org.json.JSONArray -> jsonToList(v)
                        else -> v
                    }
            }
            return map
        }

        private fun jsonToList(arr: org.json.JSONArray): List<Any?> {
            val list = mutableListOf<Any?>()
            for (i in 0 until arr.length()) {
                list.add(
                    when (val v = arr.get(i)) {
                        is JSONObject -> jsonToMap(v)
                        is org.json.JSONArray -> jsonToList(v)
                        else -> v
                    },
                )
            }
            return list
        }
    }

/**
 * MCP 客户端配置（§9.1 dyn 调用域）。
 * moduleCallTimeoutS：dyn_* FC 调用默认超时（M0 timeoutMs 参数的全局默认；模块 manifest
 * 可选 callTimeoutS 覆盖——M3.1 接入）。
 */
class McpClientModuleConfig : ModuleConfigBase() {
    @ConfigField(
        label = "dyn 模块调用超时(秒)",
        fieldType = ConfigFieldType.NUMBER,
        description = "dyn_* FC 单次调用默认超时（模块 manifest 可选 callTimeoutS 覆盖）；分钟级以上长任务应走 start_job/poll 异步模式（M0 语义门）",
        min = 10.0,
        max = 3600.0,
    )
    var moduleCallTimeoutS: Int = 60

    override fun toJson(): String =
        JSONObject().apply {
            put("moduleCallTimeoutS", moduleCallTimeoutS)
        }.toString()

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            moduleCallTimeoutS = obj.optInt("moduleCallTimeoutS", 60).coerceIn(10, 3600)
        } catch (_: Exception) {
        }
    }

    override fun defaultConfig(): ModuleConfigBase = McpClientModuleConfig()
}
