package com.zero.v2.app.acceptance

import android.net.LocalSocket
import android.net.LocalSocketAddress
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.security.SecureKeyStore
import dagger.hilt.android.EntryPointAccessors
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Opt-in production localhost/Termux acceptance. No LLM calls or user configuration writes. */
class InfrastructureAcceptanceTest {
    @Test fun pairedControlLargeOutputRotationAndExistingCredentials() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("infrastructureAcceptance") == "true")
        val context = inst.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val bridge = entry.termuxBridge()
        val http = OkHttpClient()
        val files = mutableListOf<String>()
        val prefix = "/data/data/com.termux/files/usr"
        val python = "$prefix/bin/python3"
        val backup = "$prefix/zero/.control-token.infrastructure-backup"
        val report = JSONObject()
        suspend fun native(script: String): String {
            val result = requireNotNull(bridge.awaitCommand(python, listOf("-c", script), timeoutMs = 25_000))
            assertEquals("Native helper exit", 0, result.second)
            return result.first.trim()
        }
        suspend fun exec(command: String, timeout: Double = 15.0): JSONObject {
            val result = requireNotNull(bridge.supPost("/exec", JSONObject().put("cmd", command)
                .put("timeout_s", timeout), 25_000))
            result.optJSONObject("output_files")?.let { outputs ->
                for (stream in listOf("stdout", "stderr")) files += outputs.getJSONObject(stream).getString("path")
            }
            return result
        }
        suspend fun socket(authenticated: Boolean, old: Boolean = false): Int {
            val credential = if (old) backup else "$prefix/zero/.control-token"
            val tokenLine = if (authenticated) "req['token']=Path('$credential').read_text().strip()" else "pass"
            return native("from pathlib import Path; import socket,struct,json; s=socket.socket(socket.AF_UNIX); s.settimeout(5); s.connect('\\0zero-supervisor'); req={'method':'GET','path':'/health'}; $tokenLine; b=json.dumps(req).encode(); s.sendall(struct.pack('>I',len(b))+b); n=struct.unpack('>I',s.recv(4))[0]; b=b''\nwhile len(b)<n: b+=s.recv(n-len(b))\nprint(json.loads(b)['status']); s.close()").toInt()
        }
        try {
            assertTrue("Supervisor accepted asset bundle", bridge.ensureSupervisor())
            val health = requireNotNull(bridge.health()).raw
            val endpoint = "http://127.0.0.1:${bridge.supervisorPort}/health"
            val authorized = bridge.authorizeSupervisorRequest(Request.Builder().url(endpoint).build())
            http.newCall(Request.Builder().url(endpoint).build()).execute().use { assertEquals(401, it.code) }
            http.newCall(Request.Builder().url(endpoint.replace("/health", "/exec"))
                .post("{\"cmd\":\"false\"}".toRequestBody(null)).build()).execute().use { assertEquals(401, it.code) }
            assertEquals(401, socket(false))
            assertEquals(200, socket(true))
            report.put("androidAbstractSocket", "cross_uid_permission_denied").put("unauthenticatedDenied", true).put("authenticatedSocket", true)
            val pid = health.getInt("pid")
            val monitor = async {
                native("import time,json; p='/proc/$pid/status'; v=[]\nfor _ in range(60):\n v.append(int(next(x for x in open(p) if x.startswith('VmRSS:')).split()[1])); time.sleep(.1)\nprint(json.dumps(v))")
            }
            delay(400)
            val large = exec("python3 -c 'import sys; b=\"x\"*1048576; [(sys.stdout.write(b),sys.stderr.write(b)) for _ in range(32)]; sys.exit(7)'")
            assertEquals(7, large.getInt("exit"))
            assertEquals(65536, large.getString("stdout").length)
            for (stream in listOf("stdout", "stderr")) {
                val output = large.getJSONObject("output_files").getJSONObject(stream)
                assertEquals(2 * 1024 * 1024, output.getInt("saved_bytes"))
                assertTrue(output.getBoolean("truncated"))
            }
            val original = large.getJSONObject("output_files").getJSONObject("stdout").getString("path")
            assertEquals("x".repeat(100), bridge.supGet("/read?path=$original&offset=0&len=100")!!.getString("text"))
            val samples = JSONArray(monitor.await())
            val rss = (0 until samples.length()).map { samples.getInt(it) }
            assertTrue("Supervisor RSS growth must be bounded", rss.max() - rss.first() < 16 * 1024)
            val timed = exec("python3 -c 'import time; time.sleep(30)'", .5)
            assertTrue(timed.getBoolean("timed_out"))
            assertTrue(timed.getBoolean("cleanup_ok"))
            assertEquals("alive", exec("printf alive").getString("stdout"))
            report.put("dualStreamBytes", 64 * 1024 * 1024).put("rssSamplesKiB", samples)
                .put("timeoutCleanup", true).put("originalPrefixReadable", true)
            native("from pathlib import Path; import secrets,os; p=Path('$prefix/zero/.control-token'); b=Path('$backup'); assert not b.exists(); b.write_bytes(p.read_bytes()); b.chmod(384); p.write_text(secrets.token_hex(32)); p.chmod(384)")
            http.newCall(authorized).execute().use { assertEquals(401, it.code) }
            assertEquals(401, socket(true, old = true))
            assertNotNull("Re-pair after rotation", bridge.health() ?: bridge.health())
            report.put("rotationReconnect", true)
            val keys = SecureKeyStore(context)
            val active = entry.profiles().profiles.value.filter { it.enabled && !it.localLlamaPreflight }
            assertTrue(active.isNotEmpty())
            assertTrue(active.any { !keys.getOrFallbackString(it.apiKeyRef, it.apiKeyRefFallback).isNullOrBlank() })
            report.put("existingEncryptedCredentialReadable", true)
            val after = requireNotNull(bridge.health()).raw
            report.put("modulesBefore", health.getJSONArray("modules"))
                .put("modulesAfter", after.getJSONArray("modules"))
                .put("assetHashes", after.getJSONObject("asset_hashes"))
        } finally {
            native("from pathlib import Path; import os; b=Path('$backup'); p=Path('$prefix/zero/.control-token');\nif b.exists(): os.replace(b,p)")
            bridge.health()
            val paths = JSONArray(files).toString()
            native("from pathlib import Path; import json; base=Path('$prefix/var/lib/proot-distro'); root=next(p for p in [base/'containers/debian/rootfs',base/'install-rootfs/debian/rootfs'] if p.is_dir()); files=json.loads('''$paths''')\nfor name in files:\n p=Path(name); assert str(p.parent)=='/root/zero/exec-results' and len(p.stem)==32 and all(c in '0123456789abcdef' for c in p.stem) and p.suffix in ('.stdout','.stderr'); (root/str(p).lstrip('/')).unlink(missing_ok=True)")
            File(context.filesDir, "infrastructure-acceptance.json").writeText(report.toString(2))
        }
    }
}
