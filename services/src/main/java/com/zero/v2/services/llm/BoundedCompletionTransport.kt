package com.zero.v2.services.llm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Hard transport budget also cancels blocking I/O when the caller is cancelled. */
internal object BoundedCompletionTransport {
    private const val DEADLINE_MS = 2500L
    private const val MAX_RESPONSE_BYTES = 16 * 1024L

    suspend fun execute(
        client: OkHttpClient, request: Request, outcome: (String) -> Unit = {},
    ): JSONObject? = coroutineScope {
        val call = client.newBuilder().callTimeout(DEADLINE_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build().newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        fun failed(category: String) {
            if (!isActive) {
                outcome("cancelled")
                throw CancellationException("bounded_cancelled")
            }
            outcome(category)
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) { outcome("http_${response.code}"); return@coroutineScope null }
                val source = response.body?.source() ?: run { outcome("empty_body"); return@coroutineScope null }
                if (source.request(MAX_RESPONSE_BYTES + 1)) { outcome("response_limit"); return@coroutineScope null }
                JSONObject(source.readUtf8()).also { outcome("success") }
            }
        } catch (cancelled: CancellationException) {
            outcome("cancelled")
            throw cancelled
        } catch (_: java.io.InterruptedIOException) {
            failed("timeout")
            null
        } catch (_: java.io.IOException) {
            failed("transport_error")
            null
        } catch (_: org.json.JSONException) {
            outcome("invalid_json")
            null
        } finally {
            cancellation.cancel()
            call.cancel()
        }
    }
}
