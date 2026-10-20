package com.zero.v2.app.ui.settings

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException

/** A system/OEM page may be absent or rejected; report failure after one optional fallback. */
internal fun openPermissionDestination(context: Context, intent: Intent, fallback: Intent? = null): Boolean {
    fun open(target: Intent): Boolean = try {
        context.startActivity(target)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        false
    }
    return open(intent) || (fallback != null && open(fallback))
}
