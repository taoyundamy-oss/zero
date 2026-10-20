package com.zero.v2.services.mcp

import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One stop acknowledgement boundary; only its callback mutates registration/runtime state. */
internal suspend fun confirmDynamicModuleStop(
    response: JSONObject?,
    onConfirmed: suspend () -> Unit,
): Boolean {
    currentCoroutineContext().ensureActive()
    if (response?.opt("ok") != true) return false
    onConfirmed()
    return true
}
