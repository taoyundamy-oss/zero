package com.zero.v2.app.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Retains the persisted class name, replacing the former success-only stub. */
@HiltWorker
class AutomationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val execution: AlarmWorkExecution,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = execution.run(inputData.getString(KEY_ACTION), runAttemptCount)

    companion object {
        const val KEY_ACTION = "alarm_action"
        // Legacy stub input is invalid, never acknowledged as an executed task.
        const val KEY_AUTOMATION_ID = "automation_id"
    }
}
