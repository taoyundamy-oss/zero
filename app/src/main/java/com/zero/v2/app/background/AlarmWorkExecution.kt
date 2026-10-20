package com.zero.v2.app.background

import androidx.work.ListenableWorker
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.agent.AlarmDueDispatcher
import com.zero.v2.services.agent.ModuleInitializationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Provider

/** Completion means the guarded scan finished, not that all tasks or music succeeded. */
class AlarmWorkExecution @Inject constructor(
    private val initialization: ModuleInitializationGate,
    private val dispatcher: Provider<AlarmDueDispatcher>,
    private val trace: AgentTraceManager,
) {
    suspend fun run(action: String?, attempt: Int): ListenableWorker.Result {
        if (action !in AlarmDueDispatcher.WORK_ACTIONS) return ListenableWorker.Result.failure()
        var initialized = false
        return try {
            val ready = withTimeoutOrNull(INITIALIZATION_TIMEOUT_MS) { initialization.awaitReady(); true }
            if (ready == null) return retryOrFail(attempt, "initialization_timeout")
            initialized = true
            val result = dispatcher.get().dispatch(action)
            trace.record("alarm_work", NodeType.SYSTEM_EVENT, "alarm",
                "alarm_scan_finished: success=${result.success} action=$action")
            if (result.success) ListenableWorker.Result.success() else ListenableWorker.Result.failure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!initialized) retryOrFail(attempt, error.javaClass.simpleName) else {
                trace.record("alarm_work", NodeType.ERROR, "alarm", "alarm_dispatch_failed: ${error.javaClass.simpleName}")
                ListenableWorker.Result.failure()
            }
        }
    }

    private fun retryOrFail(attempt: Int, reason: String): ListenableWorker.Result {
        trace.record("alarm_work", NodeType.ERROR, "alarm", "alarm_scan_failed: attempt=$attempt reason=$reason")
        return if (attempt < MAX_RETRIES) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
    }

    companion object {
        private const val INITIALIZATION_TIMEOUT_MS = 60_000L
        private const val MAX_RETRIES = 2
    }
}
