package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.di.ServicesModule
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.URI

/** Free, unauthenticated CF trace using the same client factory and UID as Zero LLM. */
class PhoneProxyRouteTest {
    @Test fun actualLlmRoute() {
        val runner = InstrumentationRegistry.getInstrumentation()
        check(runner is ProviderAcceptanceRunner)
        check(InstrumentationRegistry.getArguments().getString("phoneProxyRoute") == "true")
        val direct = InstrumentationRegistry.getArguments().getString("diagnosticNoProxy") == "true"
        val http = ServicesModule.provideLLMOkHttpClient().newBuilder()
            .also { if (direct) it.proxy(java.net.Proxy.NO_PROXY) }
            .retryOnConnectionFailure(false).followRedirects(false).build()
        val rows = JSONArray()
        val report = JSONObject().put("uid", android.os.Process.myUid()).put("rows", rows)
            .put("explicitNoProxy", direct)
        val output = File(runner.targetContext.filesDir, "llm-phone-proxy-route.json")
        for (host in listOf("api.cloudflare.com", "gateway.ai.cloudflare.com", "www.cloudflare.com")) {
            val url = "https://$host/cdn-cgi/trace"
            val row = JSONObject().put("host", host)
                .put("proxySelection", http.proxySelector.select(URI(url)).joinToString { it.type().name })
                .put("effectiveProxy", http.proxy?.type()?.name ?: "SYSTEM_PROXY_SELECTOR")
            rows.put(row)
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    row.put("httpStatus", response.code).put("cfRay", response.header("cf-ray"))
                    response.body!!.string().lineSequence().forEach { line ->
                        val field = line.substringBefore('=')
                        if (field in setOf("loc", "colo", "warp", "tls", "http")) row.put(field, line.substringAfter('='))
                    }
                }
            } catch (failure: Exception) { row.put("errorClass", failure.javaClass.simpleName) }
            output.writeText(report.toString(2))
        }
    }
}
