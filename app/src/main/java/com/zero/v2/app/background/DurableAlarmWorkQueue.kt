package com.zero.v2.app.background

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.zero.v2.core.moduleapi.AlarmWorkQueue
import com.zero.v2.services.agent.AlarmDueDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class DurableAlarmWorkQueue @Inject constructor(private val manager: Provider<WorkManager>) : AlarmWorkQueue {
    override suspend fun enqueue(action: String) = withContext(Dispatchers.IO) {
        require(action in AlarmDueDispatcher.WORK_ACTIONS) { "unknown_alarm_action" }
        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInputData(Data.Builder().putString(AutomationWorker.KEY_ACTION, action).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .addTag(WORK_TAG).build()
        // Append a new due event without cancelling an already running scan.
        manager.get().enqueueUniqueWork(workName(action), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .result.get(HANDOFF_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Unit
    }

    companion object {
        const val WORK_TAG = "zero_alarm_due"
        private const val HANDOFF_TIMEOUT_SECONDS = 5L
        fun workName(action: String) = "zero_alarm_due:$action"
    }
}
