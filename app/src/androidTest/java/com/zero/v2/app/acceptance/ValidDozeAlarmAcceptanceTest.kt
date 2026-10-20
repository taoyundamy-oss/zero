package com.zero.v2.app.acceptance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.zero.v2.app.background.AlarmWorkExecution
import com.zero.v2.app.background.AutomationWorker
import com.zero.v2.app.background.DurableAlarmWorkQueue
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.ExactAlarmReceiver
import com.zero.v2.services.agent.ExactAlarmScheduler
import com.zero.v2.services.agent.ModuleInitializationGate
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.inject.Provider

/** Opt-in physical alarm delivery to the production receiver, isolated business/WorkManager data. */
class ValidDozeAlarmAcceptanceTest {
    @Test fun realIdleAlarmDeliversOneActualPlanAndCommitsItsRun() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("validDozeProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = File(context.filesDir, "a8-valid-doze-ready.json")
        val receipt = File(context.filesDir, "a8-valid-doze-receipt.json")
        assertFalse("Do not overwrite a pending probe", ready.exists())
        var calls = 0
        var idleAtTool = ""
        var executedAt = 0L
        AlarmModuleGuardAcceptanceTest().fixture({
            calls++
            idleAtTool = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys deviceidle get deep"))
                .bufferedReader().use { it.readText().trim() }
            executedAt = SystemClock.elapsedRealtime()
            FunctionResult.success("isolated local plan")
        }) { _, db, dispatcher, _ ->
            val execution = AlarmWorkExecution(ModuleInitializationGate().apply { complete() },
                Provider { dispatcher }, AgentTraceManager(db.agentTraceEventDao()))
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == AutomationWorker::class.java.name) AutomationWorker(appContext, workerParameters, execution) else null
            }
            WorkManagerTestInitHelper.initializeTestWorkManager(context,
                Configuration.Builder().setWorkerFactory(factory).build())
            val work = WorkManager.getInstance(context)
            val action = ExactAlarmScheduler.ACTION_AUTOMATION_DUE
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            assertTrue(alarm.canScheduleExactAlarms())
            // Distinct request code: never replaces the production scheduler's 4002 alarm.
            val pending = PendingIntent.getBroadcast(context, 900_820,
                Intent(context, ExactAlarmReceiver::class.java).setAction(action),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val triggerAt = SystemClock.elapsedRealtime() + 45_000
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
                ready.writeText(JSONObject().put("triggerAtElapsedMs", triggerAt).put("requestCode", 900_820).toString())
                withTimeout(120_000) {
                    while (work.getWorkInfosForUniqueWork(DurableAlarmWorkQueue.workName(action)).get()
                            .none { it.state == WorkInfo.State.SUCCEEDED }) delay(50)
                }
                val infos = work.getWorkInfosForUniqueWork(DurableAlarmWorkQueue.workName(action)).get()
                val row = requireNotNull(db.automationDao().getById("isolated-dispatch"))
                receipt.writeText(JSONObject().put("calls", calls).put("runCount", row.runCount)
                    .put("workCount", infos.size).put("idleAtTool", idleAtTool)
                    .put("triggerAtElapsedMs", triggerAt).put("executedAtElapsedMs", executedAt).toString())
                assertEquals("IDLE", idleAtTool)
                assertEquals(1, calls)
                assertEquals(1, row.runCount)
                assertEquals(1, infos.size)
                assertTrue(row.nextRunAt > System.currentTimeMillis())
                assertTrue(executedAt >= triggerAt)
            } finally {
                alarm.cancel(pending); pending.cancel()
                work.cancelUniqueWork(DurableAlarmWorkQueue.workName(action)).result.get()
                ready.delete()
            }
        }
    }
}
