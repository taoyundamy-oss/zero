package com.zero.v2.services.mcp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

private const val RECEIPT_TIMEOUT_S = 3L
private const val RECEIPT_WAIT_NS = 45_000_000_000L
private const val RECEIPT_POLL_MS = 500L

/** Cancelling the owner closes the HTTP call; it never turns cancellation into a retry. */
internal suspend fun postModuleStart(
    http: OkHttpClient, url: String, body: JSONObject, readOnly: Boolean = false,
): JSONObject? {
    val builder = Request.Builder().url(url)
    if (readOnly) builder.get() else builder.post(body.toString().toRequestBody(null))
    val call = http.newCall(builder.build())
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(uncertainStart("start_transport_failure"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    val raw = it.body?.string().orEmpty()
                    when {
                        !it.isSuccessful -> uncertainStart("start_http_${it.code}")
                        raw.isBlank() -> uncertainStart("start_reply_empty")
                        else -> JSONObject(raw).takeIf { json -> json.hasKnownStartResult() }
                            ?: uncertainStart("start_reply_invalid")
                    }
                } }.getOrElse { uncertainStart("start_reply_invalid") }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
}

private fun uncertainStart(reason: String) = JSONObject().put("ok", false).put("uncertain", true).put("error", reason)

private fun JSONObject.hasKnownStartResult(): Boolean {
    if (opt("ok") !is Boolean) return false
    return !getBoolean("ok") || (opt("endpoint") as? String)?.isNotBlank() == true
}

/** Ambiguous responses are reconciled with this exact request's live receipt, never /health or an old endpoint. */
internal suspend fun confirmedModuleStart(http: OkHttpClient, url: String, body: JSONObject): JSONObject? {
    val requestId = java.util.UUID.randomUUID().toString().replace("-", "")
    body.put("request_id", requestId)
    val result = postModuleStart(http, url, body)
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    if (result != null && !result.optBoolean("uncertain")) return result
    val lookup = http.newBuilder().callTimeout(RECEIPT_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(RECEIPT_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS).build()
    return lookupReceipt(lookup, url, requestId) ?: uncertainStart("start_response_unconfirmed")
        .put("cause", result?.optString("error"))
}

private suspend fun lookupReceipt(http: OkHttpClient, url: String, requestId: String): JSONObject? {
    val deadline = System.nanoTime() + RECEIPT_WAIT_NS
    do {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val statusUrl = url.removeSuffix("/start") + "/start-status?request_id=$requestId"
        val status = postModuleStart(http, statusUrl, JSONObject(), true)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (status?.matchesReceipt(requestId) == true) return status
        if (status?.optString("error") != "control_plane_busy") break
        kotlinx.coroutines.delay(RECEIPT_POLL_MS)
    } while (System.nanoTime() < deadline)
    return null
}

private fun JSONObject.matchesReceipt(requestId: String): Boolean {
    val accepted = optBoolean("ok") && optBoolean("reconciled")
    return accepted && optString("request_id") == requestId && optString("endpoint").isNotBlank()
}
