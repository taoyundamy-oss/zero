package com.zero.v2.app.acceptance

import android.os.Process
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.AutomationEntity
import com.zero.v2.services.module.ExecutionPlan
import com.zero.v2.services.module.PlanStep
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class ColdWorkAcceptanceTest {
    @Test fun physicalQueueColdCreation() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("coldWorkPhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = File(context.filesDir, ColdPlanAcceptanceWorker.READY)
        val receipt = File(context.filesDir, ColdPlanAcceptanceWorker.RECEIPT)
        // Actual Application Configuration.Provider and Hilt factory, no test delegate.
        val work = WorkManager.getInstance(context)
        if (phase == "seed") {
            assertFalse(ready.exists())
            assertFalse(receipt.exists())
            assertFalse(context.getDatabasePath(ColdPlanAcceptanceWorker.DB).exists())
            assertTrue(work.getWorkInfosForUniqueWork(ColdPlanAcceptanceWorker.WORK).get().isEmpty())
            val db = Room.databaseBuilder(context, ZeroDatabase::class.java, ColdPlanAcceptanceWorker.DB).build()
            try {
                db.automationDao().insert(AutomationEntity("isolated-dispatch", "隔离冷恢复", "本地验收",
                    ExecutionPlan("cold plan", listOf(PlanStep(1, "echo", "isolated_echo"))).toJson(),
                    scheduleType = "INTERVAL", scheduleValue = "3600", nextRunAt = 0))
            } finally { db.close() }
            val request = OneTimeWorkRequestBuilder<ColdPlanAcceptanceWorker>()
                .setInputData(Data.Builder().putString("probe", "a8-cold-plan")
                    .putString("alarm_action", "com.zero.v2.action.AUTOMATION_DUE").build())
                .setInitialDelay(60, TimeUnit.SECONDS).build()
            work.enqueueUniqueWork(ColdPlanAcceptanceWorker.WORK, ExistingWorkPolicy.KEEP, request).result.get()
            assertEquals(WorkInfo.State.ENQUEUED, requireNotNull(work.getWorkInfoById(request.id).get()).state)
            assertTrue(File(context.noBackupFilesDir, "androidx.work.workdb").exists())
            ready.writeText(JSONObject().put("uuid", request.id.toString())
                .put("pid", Process.myPid()).put("uid", Process.myUid()).toString())
            delay(120_000)
            fail("Driver failed to terminate seed process")
        } else {
            val marker = JSONObject(ready.readText())
            val result = JSONObject(receipt.readText())
            assertEquals(marker.getString("uuid"), result.getString("uuid"))
            assertNotEquals(marker.getInt("pid"), result.getInt("pid"))
            assertEquals(1, result.getInt("calls"))
            assertEquals(1, result.getInt("runCount"))
            assertEquals(WorkInfo.State.SUCCEEDED, requireNotNull(work.getWorkInfoById(UUID.fromString(marker.getString("uuid"))).get()).state)
            assertEquals(1, work.getWorkInfosForUniqueWork(ColdPlanAcceptanceWorker.WORK).get().size)
            assertTrue(context.deleteDatabase(ColdPlanAcceptanceWorker.DB))
            assertTrue(ready.delete())
            // Keep the completed WorkSpec and receipt as evidence; never prune other work.
        }
    }
}
