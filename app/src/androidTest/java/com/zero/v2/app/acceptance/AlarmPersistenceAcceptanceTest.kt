package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zero.v2.app.background.AutomationWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit two-process probe. Invalid input exercises the real factory without personal scans. */
class AlarmPersistenceAcceptanceTest {
    @Test fun committedWorkerSurvivesExplicitStopAndNormalRestart() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("alarmPersistencePhase")
        assumeTrue(phase == "enqueue" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.filesDir, "acceptance-alarm-persistence-id")
        val manager = WorkManager.getInstance(context)
        if (phase == "enqueue") {
            assertFalse("Do not overwrite an unfinished probe", marker.exists())
            val request = OneTimeWorkRequestBuilder<AutomationWorker>()
                .setInitialDelay(45, TimeUnit.SECONDS)
                .addTag("acceptance_alarm_persistence").build()
            manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
            marker.writeText(request.id.toString())
            assertEquals(WorkInfo.State.ENQUEUED, requireNotNull(manager.getWorkInfoById(request.id).get()).state)
        } else {
            assertTrue("First commit the probe in a separate process", marker.exists())
            val id = UUID.fromString(marker.readText())
            val info = requireNotNull(manager.getWorkInfoById(id).get(10, TimeUnit.SECONDS))
            // Do not wait or enqueue here; inspect the original committed request after restart.
            assertEquals(WorkInfo.State.FAILED, info.state)
            assertEquals("Exactly one execution, without retry", 1, info.runAttemptCount)
            assertTrue(marker.delete())
        }
    }
}
