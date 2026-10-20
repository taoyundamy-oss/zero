package com.zero.v2.services.mcp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private const val BUSY_WAIT_MS = 45_000L
private const val BUSY_POLL_MS = 500L
private const val TRANSIENT_PAUSE_MS = 1_000L
private const val NANOS_PER_MS = 1_000_000L

/** Busy is a rejected request, never a queued mutation. Permanent failures are not retried. */
internal suspend fun startDynamicModuleWithRetry(
    request: suspend () -> JSONObject?,
    emergencyStopped: () -> Boolean,
    nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    pause: suspend (Long) -> Unit = { delay(it) },
): JSONObject? {
    val busyDeadline = nowMs() + BUSY_WAIT_MS
    var transientFailures = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = if (emergencyStopped()) {
            JSONObject().put("ok", false).put("error", "emergency_stop")
        } else {
            request()
        }
        currentCoroutineContext().ensureActive()
        if (result?.optBoolean("ok") == true) return result
        val error = result?.optString("error")
        when {
            error == "control_plane_busy" && nowMs() < busyDeadline -> pause(BUSY_POLL_MS)
            (result == null || error == "health_check_failed") && transientFailures++ == 0 -> pause(TRANSIENT_PAUSE_MS)
            else -> return result
        }
    }
}
