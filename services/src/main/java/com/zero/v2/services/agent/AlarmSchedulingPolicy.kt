package com.zero.v2.services.agent

import java.util.concurrent.CancellationException

/** One submission attempt. Platform callbacks retain the same PendingIntent identity. */
internal object AlarmSchedulingPolicy {
    enum class Mode { EXACT, WINDOW, FAILED }
    data class Result(val mode: Mode, val reason: String? = null)

    fun submit(canExact: () -> Boolean, exact: () -> Unit, window: () -> Unit): Result =
        runCatching {
            val exactSubmitted = try {
                if (canExact()) {
                    exact()
                    true
                } else {
                    false
                }
            } catch (_: SecurityException) {
                false
            }
            if (exactSubmitted) {
                Result(Mode.EXACT)
            } else {
                window()
                Result(Mode.WINDOW, "exact_permission_unavailable")
            }
        }.getOrElse { failure ->
            if (failure is CancellationException || failure !is RuntimeException) throw failure
            Result(Mode.FAILED, failure.javaClass.simpleName)
        }
}
