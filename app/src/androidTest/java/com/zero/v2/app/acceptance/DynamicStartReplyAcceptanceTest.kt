package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.common.ModuleReleaseManifest
import com.zero.v2.core.data.entity.DynamicModuleEntity
import com.zero.v2.services.termux.TermuxBridgeConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Real supervisor accepts an owned start, but its successful HTTP reply is lost. */
class DynamicStartReplyAcceptanceTest {
    @Test fun lostSuccessfulReplyStillMountsExactlyOnceAndRegistersCallableTools() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val id = "a8_reply_${System.currentTimeMillis()}"
        val code = "import zero_tool\n@zero_tool.tool\ndef echo(value:int):\n    return {'value':value}\nzero_tool.serve()\n"
        val module = DynamicModuleEntity(id, source = code, createdAt = System.currentTimeMillis())
        val config = entry.termux().config as TermuxBridgeConfig
        val originalPort = config.supervisorPort
        val proxy = LostReplyProxy(originalPort, id)
        try {
            assertTrue("Supervisor/bootstrap must be available before owned publication", entry.termux().ensureSupervisor())
            val release = provider.releaseManager().publish(id, code, ModuleReleaseManifest(sdkRange = ">=1.1,<2"))
            assertTrue("Owned release failed: $release", release.ok)
            entry.lifecycleManager().safeCall(entry.mcp(), "reply-acceptance-start") { entry.mcp().onStart() }
            config.supervisorPort = proxy.port
            val mounted = entry.lifecycleManager().safeCall(entry.mcp(), "reply-acceptance-mount") {
                entry.mcp().mountDynamicModule(module)
            }
            config.supervisorPort = originalPort
            val runtime = requireNotNull(entry.termux().health()).raw.getJSONArray("modules")
            assertTrue((0 until runtime.length()).any { runtime.getJSONObject(it).let { r ->
                r.optString("id") == id && r.optString("state") == "ACTIVE"
            } })
            assertEquals("Accepted running module must not become unmounted after a lost reply", true, mounted)
            assertEquals("ACTIVE", provider.database().dynamicModuleDao().getById(id)?.state)
            assertEquals("Ambiguous successful start must not restart an accepted process", 1, proxy.starts.get())
            val result = entry.dispatcher().execute("dyn_${id}_echo", mapOf("value" to 7))
            assertTrue(result.errorMessage, result.success)
            assertEquals(7, JSONObject(result.data).getInt("value"))
        } finally {
            config.supervisorPort = originalPort
            entry.lifecycleManager().safeCall(entry.mcp(), "reply-acceptance-unmount") { entry.mcp().unmountDynamicModule(module) }
            provider.database().dynamicModuleDao().delete(id)
            proxy.close()
        }
    }
}

private class LostReplyProxy(private val originalPort: Int, private val id: String) : AutoCloseable {
    private val listener = ServerSocket(0)
    val port get() = listener.localPort
    val starts = AtomicInteger()
    private val pool = Executors.newCachedThreadPool()
    private val http = OkHttpClient.Builder().readTimeout(150, java.util.concurrent.TimeUnit.SECONDS).build()
    init {
        pool.submit {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                pool.submit { socket.use { serve(it) } }
            }
        }
    }
    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun line(): String = buildString {
            while (true) {
                val c = input.read()
                if (c < 0 || c == 10) break
                if (c != 13) append(c.toChar())
            }
        }
        val request = line().split(' ')
        if (request.size < 2) return
        var length = 0
        var authorization: String? = null
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Authorization:", true)) authorization = header.substringAfter(':').trim()
            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return
            offset += read
        }
        val builder = Request.Builder().url("http://127.0.0.1:$originalPort${request[1]}")
        authorization?.let { builder.header("Authorization", it) }
        if (request[0] == "POST") builder.post(body.toRequestBody(null))
        http.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: byteArrayOf()
            val dropped = request[1] == "/module/$id/start" &&
                JSONObject(bytes.toString(Charsets.UTF_8)).optBoolean("ok")
            if (request[1] == "/module/$id/start") starts.incrementAndGet()
            val output = socket.getOutputStream()
            val outgoing = if (dropped) byteArrayOf() else bytes
            output.write("HTTP/1.1 ${response.code} Result\r\nContent-Type: application/json\r\nContent-Length: ${outgoing.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(outgoing)
            output.flush()
        }
    }
    override fun close() { listener.close(); pool.shutdownNow() }
}
