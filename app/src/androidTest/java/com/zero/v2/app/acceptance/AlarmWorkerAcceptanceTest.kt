package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.zero.v2.app.background.AutomationWorker
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AlarmWorkerAcceptanceTest {
    @Test fun unknownLegacyTaskMustNotReportSuccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = EntryPointAccessors.fromApplication(context, WorkerAcceptanceEntryPoint::class.java).workerFactory()
        val worker = TestListenableWorkerBuilder<AutomationWorker>(context,
            inputData = Data.Builder().putString(AutomationWorker.KEY_AUTOMATION_ID, "isolated-missing-id").build())
            .setWorkerFactory(factory).build()
        assertEquals("A worker must not acknowledge a task it never executed", ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test fun applicationMustProvideTheHiltWorkManagerConfiguration() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue("Production Application must configure Hilt workers", app is Configuration.Provider)
        val config = (app as Configuration.Provider).workManagerConfiguration
        assertTrue(config.workerFactory is androidx.hilt.work.HiltWorkerFactory)
        val worker = TestListenableWorkerBuilder<AutomationWorker>(app)
            .setWorkerFactory(config.workerFactory).build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        val provider = app.packageManager.getProviderInfo(
            android.content.ComponentName(app, "androidx.startup.InitializationProvider"),
            android.content.pm.PackageManager.GET_META_DATA,
        )
        assertFalse(provider.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true)
    }
}
