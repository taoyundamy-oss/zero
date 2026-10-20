package com.zero.v2.app.acceptance

import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.zero.v2.app.background.AlarmWorkExecution
import com.zero.v2.app.background.AutomationWorker
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.ExactAlarmScheduler
import com.zero.v2.services.agent.ModuleInitializationGate
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.inject.Provider

/** Opt-in disk business recovery. The driver kills the seed process; no personal DB or scheduler. */
class DiskAlarmRecoveryAcceptanceTest {
    @Test fun recoverCommittedPlanInAnotherProcess() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("diskRecoveryPhase")
        assumeTrue(phase == "seed" || phase == "recover")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "a8_disk_recovery_fixture.db"
        val ready = File(context.filesDir, "a8-disk-recovery-ready.json")
        val receipt = File(context.filesDir, "a8-disk-recovery-receipt.json")
        if (phase == "seed") {
            assertFalse("Never overwrite an earlier fixture", context.getDatabasePath(name).exists())
            assertFalse(ready.exists())
        } else {
            assertTrue(context.getDatabasePath(name).exists())
            assertTrue(ready.exists())
        }
        var calls = 0
        AlarmModuleGuardAcceptanceTest().fixture({ calls++; FunctionResult.success("local recovered plan") },
            persistentName = name, seed = phase == "seed") { _, db, dispatcher, _ ->
            if (phase == "seed") {
                assertEquals(0, db.automationDao().getById("isolated-dispatch")!!.runCount)
                ready.writeText(JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid()).toString())
                // The driver intentionally terminates this process after the committed marker.
                delay(120_000)
                fail("Driver did not terminate the seed process")
            } else {
                val seedPid = JSONObject(ready.readText()).getInt("pid")
                assertNotEquals(seedPid, Process.myPid())
                assertEquals(0, db.automationDao().getById("isolated-dispatch")!!.runCount)
                val execution = AlarmWorkExecution(ModuleInitializationGate().apply { complete() },
                    Provider { dispatcher }, AgentTraceManager(db.agentTraceEventDao()))
                val factory = object : WorkerFactory() {
                    override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
                        if (name == AutomationWorker::class.java.name) AutomationWorker(context, params, execution) else null
                }
                fun worker() = TestListenableWorkerBuilder<AutomationWorker>(context,
                    inputData = Data.Builder().putString(AutomationWorker.KEY_ACTION, ExactAlarmScheduler.ACTION_AUTOMATION_DUE).build())
                    .setWorkerFactory(factory).build()
                assertEquals(ListenableWorker.Result.success(), worker().doWork())
                assertEquals(ListenableWorker.Result.success(), worker().doWork())
                val row = db.automationDao().getById("isolated-dispatch")!!
                assertEquals(1, calls)
                assertEquals(1, row.runCount)
                assertTrue(row.nextRunAt > System.currentTimeMillis())
                receipt.writeText(JSONObject().put("seedPid", seedPid).put("recoveryPid", Process.myPid())
                    .put("calls", calls).put("runCount", row.runCount).put("nextRunAt", row.nextRunAt).toString())
            }
        }
        if (phase == "recover") {
            assertTrue(context.deleteDatabase(name))
            assertTrue(ready.delete())
        }
    }
}
