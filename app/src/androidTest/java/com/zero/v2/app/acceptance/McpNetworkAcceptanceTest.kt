package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ActionPermission
import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.moduleapi.IZeroModule
import com.zero.v2.core.moduleapi.ToolRegistrar
import com.zero.v2.services.mcp.*
import dagger.hilt.android.EntryPointAccessors
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real TCP/HTTP and production MCP owner. Only isolated connection preferences and registration are mutated. */
class McpNetworkAcceptanceTest {
    @Test fun managementProbeReportsListFailureAndRejectedAddReturnsVisibleFailure() = runBlocking {
        Fixture().use { f ->
            val tools = f.module.contributions()
            val probe = tools.single { it.definition.name == "mcp_test_connection" }
            val failed = probe.handler(mapOf("url" to f.server("/listfail").url))
            assertFalse(failed.success)
            assertTrue(failed.errorMessage!!.contains("发现失败"))
            f.backend.reject = true
            val added = tools.single { it.definition.name == "mcp_add_server" }.handler(
                mapOf("name" to "Owned add", "url" to f.server("/ok").url, "api_key" to "owned-new"))
            assertFalse(added.success)
            assertTrue(added.errorMessage!!.contains("保存失败"))
            assertTrue(f.store.servers.value.isEmpty())
        }
    }

    @Test fun productionUiReportsNetworkFailureAndCancelCannotClearNewRefreshBusyState() = runBlocking {
        Fixture().use { f ->
            val inst = InstrumentationRegistry.getInstrumentation()
            val lifecycle = EntryPointAccessors.fromApplication(f.context,
                RegenerationAcceptanceEntryPoint::class.java).lifecycleManager()
            val owner = androidx.lifecycle.ViewModelStore()
            lateinit var vm: com.zero.v2.app.ui.settings.McpServersViewModel
            f.store.upsert(f.server("/fail"))
            inst.runOnMainSync {
                vm = com.zero.v2.app.ui.settings.McpServersViewModel(f.store, f.keys,
                    com.zero.v2.app.ui.settings.ManagedMcpConnectionActions(f.module, lifecycle))
                owner.put("owned", vm)
                vm.refresh()
            }
            try {
                await { !vm.state.value.refreshing && vm.state.value.error != null }
                assertTrue(vm.state.value.error!!.contains("发现失败"))
                f.store.upsert(f.server("/delay"))
                inst.runOnMainSync { vm.refresh() }
                assertTrue(f.http.entered.await(5, TimeUnit.SECONDS))
                inst.runOnMainSync { vm.cancelRefresh(); vm.refresh() }
                delay(150)
                assertTrue("Cancelled old job must not clear new busy state", vm.state.value.refreshing)
                f.http.release.countDown()
                await { !vm.state.value.refreshing && vm.state.value.status?.contains("发现完成") == true }
                assertNull(vm.state.value.error)
                assertEquals(1, vm.state.value.discoveredTools)
            } finally { inst.runOnMainSync { owner.clear() } }
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        repeat(200) { if (condition()) return; delay(25) }
        assertTrue("Owned UI network state did not settle", condition())
    }

    @Test fun partialSuccessEmptyListAndListFailureHaveDistinctReports() = runBlocking {
        Fixture().use { f ->
            f.store.upsert(f.server("/ok").copy(id = "good"))
            f.store.upsert(f.server("/empty").copy(id = "empty"))
            f.store.upsert(f.server("/listfail").copy(id = "failed"))
            val report = f.module.refreshAllTools()
            assertFalse(report.successful)
            assertEquals(listOf("failed"), report.failedIds)
            assertEquals(listOf("empty"), report.emptyIds)
            assertEquals(1, report.toolCount)
            assertTrue(f.registry.names.containsKey("mcp_good_echo"))
            assertFalse(f.refreshTool().success)
            f.store.remove("failed")
            f.store.remove("good")
            val empty = f.module.refreshAllTools()
            assertTrue(empty.successful)
            assertEquals(0, empty.toolCount)
            assertTrue(empty.message().contains("未提供工具"))
        }
    }

    @Test fun changedOwnerDiscardsOldResponseAndQueuedRefreshPublishesOnlyNewOwner() = runBlocking {
        Fixture().use { f ->
            val old = f.server("/delay")
            f.store.upsert(old)
            val first = async(Dispatchers.IO) { f.module.refreshAllTools() }
            assertTrue(f.http.entered.await(5, TimeUnit.SECONDS))
            f.store.upsert(f.server("/ok").copy(name = "New owner", autoPermission = true))
            val second = async(Dispatchers.IO) { f.module.refreshAllTools() }
            delay(100)
            assertFalse(second.isCompleted)
            f.http.release.countDown()
            assertTrue(first.await().stale)
            assertTrue(second.await().successful)
            assertEquals("[New owner] Owned echo", f.registry.names.getValue("mcp_owned_echo").description)
        }
    }

    @Test fun sameConfigurationNewGenerationDiscardsLateResponseAndOldHandlerCannotCallDeletedOwner() = runBlocking {
        Fixture().use { f ->
            val original = f.server("/ok").copy(apiKeyRef = "owned_ref")
            f.store.upsert(original)
            f.module.refreshAllTools()
            val handler = f.registry.handlers.getValue("mcp_owned_echo")
            f.http.delay = true
            val job = async(Dispatchers.IO) { f.module.refreshAllTools() }
            assertTrue(f.http.entered.await(5, TimeUnit.SECONDS))
            // Credential updates can retain the same configuration/ref, so equality alone is insufficient.
            f.store.saveWithCredential(original, "owned-new-key", f.keys)
            f.http.release.countDown()
            assertTrue(job.await().stale)
            f.store.remove("owned")
            assertFalse(handler(emptyMap()).success)
            assertEquals(0, f.http.toolCalls)
        }
    }
    @Test fun http500MustNotReportDiscoverySuccess() = runBlocking {
        Fixture().use { f ->
            f.store.upsert(f.server("/fail"))
            val result = f.refreshTool()
            assertFalse("HTTP 500 must not be reported as successful discovery", result.success)
            assertTrue(f.http.requests > 0)
        }
    }

    @Test fun lateToolsCannotRegisterDeletedConnection() = runBlocking {
        Fixture().use { f ->
            f.store.upsert(f.server("/delay"))
            val job = async(Dispatchers.IO) { f.module.refreshAllTools() }
            assertTrue(f.http.entered.await(5, TimeUnit.SECONDS))
            f.store.remove("owned")
            f.http.release.countDown()
            job.await()
            assertTrue("Deleted connection must not be reintroduced by late HTTP response", f.registry.names.isEmpty())
        }
    }

    @Test fun cancelledDiscoveryKeepsConfirmedToolsAndNeverRegistersLateCandidate() = runBlocking {
        Fixture().use { f ->
            val server = f.server("/ok")
            f.store.upsert(server)
            f.module.refreshAllTools()
            assertTrue(f.registry.names.containsKey("mcp_owned_echo"))
            // Same connection snapshot; only the server response changes.
            f.http.delay = true
            val job = launch(Dispatchers.IO) { f.module.refreshAllTools() }
            assertTrue(f.http.entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            f.http.release.countDown()
            delay(100)
            assertTrue("Cancelled refresh must preserve previously confirmed tools", f.registry.names.containsKey("mcp_owned_echo"))
            assertEquals(1, f.module.discoveredCount.value)
        }
    }

    private class Fixture : AutoCloseable {
        val http = OwnedHttp()
        val registry = OwnedRegistrar()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val backend = RejectingPreferences("[]")
        val owned = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = backend.preferences
        }
        val store = McpServerStore(owned, entry.traces())
        val keys = OwnedCredentials()
        val trust = TrustConfig(store, keys, entry.traces())
        val module = McpClientModule(store, registry, keys, entry.traces(), trust,
            entry.releaseManager(), entry.database().dynamicModuleDao(), McpResultDecoder(context))
        fun server(path: String) = McpServerConfig("owned", "Owned HTTP", "http://127.0.0.1:${http.port}$path")
        suspend fun refreshTool() = module.contributions().single { it.definition.name == "mcp_refresh_tools" }.handler(emptyMap())
        override fun close() { trust.close(); http.close() }
    }
}

private class OwnedRegistrar : ToolRegistrar {
    val names = ConcurrentHashMap<String, FunctionDefinition>()
    val handlers = ConcurrentHashMap<String, suspend (Map<String, Any?>) -> FunctionResult>()
    override fun register(definition: FunctionDefinition, sourceModuleId: String, permission: ActionPermission,
        handler: suspend (Map<String, Any?>) -> FunctionResult) {
        names[definition.name] = definition; handlers[definition.name] = handler
    }
    override fun unregister(name: String) { names.remove(name); handlers.remove(name) }
    override fun registerContributions(module: IZeroModule) = 0
}

private class OwnedHttp : AutoCloseable {
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    private val pool = Executors.newCachedThreadPool()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    @Volatile var delay = false
    @Volatile var requests = 0
    @Volatile var toolCalls = 0
    init {
        pool.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                pool.submit { runCatching { socket.use { serve(it) } } }
            }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val path = input.readLine().split(' ')[1]
        var length = 0
        while (true) {
            val line = input.readLine()
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
        }
        val chars = CharArray(length)
        var read = 0
        while (read < length) { val n = input.read(chars, read, length - read); check(n > 0); read += n }
        val request = JSONObject(String(chars))
        requests++
        val method = request.getString("method")
        if (method == "tools/call") toolCalls++
        if ((path == "/delay" || delay) && method == "tools/list") {
            entered.countDown()
            check(release.await(8, TimeUnit.SECONDS))
        }
        val result = when (method) {
            "tools/list" -> JSONObject().put("tools", if (path == "/empty") JSONArray() else
                JSONArray().put(JSONObject().put("name", "echo").put("description", "Owned echo")
                    .put("inputSchema", JSONObject().put("type", "object"))))
            else -> JSONObject().put("protocolVersion", "2024-11-05").put("capabilities", JSONObject())
        }
        val body = JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id"))
            .put("result", result).toString().toByteArray(Charsets.UTF_8)
        val status = if (path == "/fail" || (path == "/listfail" && method == "tools/list")) "500 Error" else "200 OK"
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }
    override fun close() { release.countDown(); server.close(); pool.shutdownNow() }
}
