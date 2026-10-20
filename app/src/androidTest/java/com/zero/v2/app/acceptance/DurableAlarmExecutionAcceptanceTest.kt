package com.zero.v2.app.acceptance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
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
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

class DurableAlarmExecutionAcceptanceTest {
    private val action = ExactAlarmScheduler.ACTION_AUTOMATION_DUE
    private fun factory(execution: AlarmWorkExecution) = object : WorkerFactory() {
        override fun createWorker(context: Context, name: String, params: WorkerParameters): ListenableWorker? =
            if (name == AutomationWorker::class.java.name) AutomationWorker(context, params, execution) else null
    }
    private fun worker(execution: AlarmWorkExecution): AutomationWorker {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return TestListenableWorkerBuilder<AutomationWorker>(context,
            inputData = Data.Builder().putString(AutomationWorker.KEY_ACTION, action).build())
            .setWorkerFactory(factory(execution)).build()
    }

    @Test fun workerWaitsForInitializationThenRunsActualPlan() = runBlocking {
        AlarmModuleGuardAcceptanceTest().fixture({ FunctionResult.success("local") }) { _, db, dispatch, _ ->
            val gate = ModuleInitializationGate()
            val execution = AlarmWorkExecution(gate, Provider { dispatch }, AgentTraceManager(db.agentTraceEventDao()))
            val result = async(start = CoroutineStart.UNDISPATCHED) { worker(execution).doWork() }
            assertFalse(result.isCompleted)
            assertEquals(0, db.automationDao().getById("isolated-dispatch")!!.runCount)
            gate.complete()
            assertEquals(ListenableWorker.Result.success(), withTimeout(5000) { result.await() })
            assertEquals(1, db.automationDao().getById("isolated-dispatch")!!.runCount)
        }
    }

    @Test fun workerHonorsDisabledModuleAndDoesNotAcknowledgeIt() = runBlocking {
        AlarmModuleGuardAcceptanceTest().fixture({ FunctionResult.success("local") }) { module, db, dispatch, manager ->
            val gate = ModuleInitializationGate().apply { complete() }
            manager.setUserEnabled(module.metadata.id, false)
            val execution = AlarmWorkExecution(gate, Provider { dispatch }, AgentTraceManager(db.agentTraceEventDao()))
            assertEquals(ListenableWorker.Result.failure(), worker(execution).doWork())
            assertEquals(0, db.automationDao().getById("isolated-dispatch")!!.runCount)
        }
    }

    @Test fun cancellationWhileWaitingDoesNotCancelTheSharedInitialization() = runBlocking {
        AlarmModuleGuardAcceptanceTest().fixture({ FunctionResult.success("local") }) { _, db, dispatch, _ ->
            val gate = ModuleInitializationGate()
            val execution = AlarmWorkExecution(gate, Provider { dispatch }, AgentTraceManager(db.agentTraceEventDao()))
            val job = launch(start = CoroutineStart.UNDISPATCHED) { worker(execution).doWork() }
            job.cancelAndJoin()
            assertEquals(0, db.automationDao().getById("isolated-dispatch")!!.runCount)
            gate.complete()
            assertEquals(ListenableWorker.Result.success(), worker(execution).doWork())
        }
    }

    @Test fun initializationFailureHasBoundedRetriesAndNeverResolvesBusinessProviders() = runBlocking {
        AlarmModuleGuardAcceptanceTest().fixture({ FunctionResult.success("local") }) { _, db, _, _ ->
            val gate = ModuleInitializationGate().apply { fail(IllegalStateException("isolated initialization")) }
            val execution = AlarmWorkExecution(gate, Provider { error("Must not resolve dispatch before ready") },
                AgentTraceManager(db.agentTraceEventDao()))
            assertEquals(ListenableWorker.Result.retry(), execution.run(action, 0))
            assertEquals(ListenableWorker.Result.failure(), execution.run(action, 2))
            assertEquals(0, db.automationDao().getById("isolated-dispatch")!!.runCount)
        }
    }

    @Test fun toolTimeoutPropagatesCancellationWithoutAdvancingTask() = runBlocking {
        AlarmModuleGuardAcceptanceTest().fixture({ withTimeout(1) { delay(100); FunctionResult.success("unreachable") } }) {
            module, db, dispatch, manager ->
            val gate = ModuleInitializationGate().apply { complete() }
            val execution = AlarmWorkExecution(gate, Provider { dispatch }, AgentTraceManager(db.agentTraceEventDao()))
            var cancelled = false
            try { worker(execution).doWork() } catch (_: TimeoutCancellationException) { cancelled = true }
            assertTrue("Business cancellation must not become a retry or success", cancelled)
            assertEquals(0, db.automationDao().getById("isolated-dispatch")!!.runCount)
            assertTrue(manager.isOperational(module.metadata.id))
        }
    }

    @Test fun realBroadcastFinishesAfterHandoffAndSecondWakeupDoesNotCancelTheFirst() = runBlocking {
        var calls = 0
        AlarmModuleGuardAcceptanceTest().fixture({ calls++; FunctionResult.success("local") }) { _, db, dispatch, _ ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val gate = ModuleInitializationGate()
            val execution = AlarmWorkExecution(gate, Provider { dispatch }, AgentTraceManager(db.agentTraceEventDao()))
            WorkManagerTestInitHelper.initializeTestWorkManager(context,
                Configuration.Builder().setWorkerFactory(factory(execution)).build())
            val manager = WorkManager.getInstance(context)
            suspend fun broadcast() {
                val done = CompletableDeferred<Unit>()
                context.sendOrderedBroadcast(Intent(context, ExactAlarmReceiver::class.java).setAction(action), null,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) { done.complete(Unit) }
                    }, null, 0, null, null)
                withTimeout(5000) { done.await() }
            }
            try {
                broadcast()
                broadcast()
                val before = manager.getWorkInfosForUniqueWork(DurableAlarmWorkQueue.workName(action)).get()
                assertEquals("Both broadcasts must be committed before finishing", 2, before.size)
                assertFalse(before.any { it.state == androidx.work.WorkInfo.State.CANCELLED })
                assertEquals(0, db.automationDao().getById("isolated-dispatch")!!.runCount)
                gate.complete()
                withTimeout(5000) {
                    while (!manager.getWorkInfosForUniqueWork(DurableAlarmWorkQueue.workName(action)).get()
                            .all { it.state == androidx.work.WorkInfo.State.SUCCEEDED }) delay(20)
                }
                assertEquals(1, calls)
                assertEquals(1, db.automationDao().getById("isolated-dispatch")!!.runCount)
            } finally {
                manager.cancelUniqueWork(DurableAlarmWorkQueue.workName(action)).result.get()
            }
        }
    }
}
