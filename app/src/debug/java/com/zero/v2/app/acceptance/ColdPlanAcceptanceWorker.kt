package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.zero.v2.app.background.AlarmWorkExecution
import com.zero.v2.app.background.AutomationWorker
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.moduleapi.*
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.agent.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Debug only: independent persisted business, reached via real Hilt/WorkManager cold creation. */
@HiltWorker
class ColdPlanAcceptanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted private val params: WorkerParameters,
    private val assembly: AutomationAcceptanceFactory,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val file = File(context.filesDir, READY)
        if (!file.exists() || inputData.getString("probe") != "a8-cold-plan") return Result.failure()
        val marker = JSONObject(file.readText())
        if (marker.getString("uuid") != params.id.toString()) return Result.failure()
        if (marker.getInt("pid") == android.os.Process.myPid()) return Result.failure()
        if (!context.getDatabasePath(DB).exists()) return Result.failure()
        val db = Room.databaseBuilder(context, ZeroDatabase::class.java, DB).build()
        var calls = 0
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), worker)
        val chat = ChatBusImpl()
        val trace = AgentTraceManager(db.agentTraceEventDao())
        val runtime = AgentRuntime(SystemBusImpl(), chat, trace, store, dagger.Lazy { error("No provider permitted") })
        val preferenceName = "a8_isolated_alarm_guard_budget"
        context.deleteSharedPreferences(preferenceName)
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
            override fun getSystemService(name: String): Any? {
                check(name != Context.ALARM_SERVICE) { "No personal alarms permitted" }
                return super.getSystemService(name)
            }
        }
        val tools = object : ToolRuntime {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
            override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult {
                check(name == "isolated_echo")
                calls++; return FunctionResult.success("isolated cold local plan")
            }
            override suspend fun execute(toolCall: ToolCall): FunctionResult = error("No model tool calls")
            override suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult> = error("No batches")
        }
        val module = assembly.create(isolated, db, store, runtime, chat, tools)
        val catalog = object : FunctionCatalog {
            override fun getAllDefinitions() = emptyList<FunctionDefinition>()
            override fun getEntriesByModule(moduleId: String) = emptyList<FunctionEntry>()
        }
        val registrar = object : ToolRegistrar {
            override fun register(definition: FunctionDefinition, sourceModuleId: String, permission: ActionPermission, handler: suspend (Map<String, Any?>) -> FunctionResult) = Unit
            override fun unregister(name: String) = Unit
            override fun registerContributions(module: IZeroModule) = 0
        }
        val manager = ModuleLifecycleManager(setOf(module), chat, store, trace, catalog, registrar)
        val dispatcher = AlarmDueDispatcher(javax.inject.Provider { error("Reminder provider must not be resolved for automation") },
            javax.inject.Provider { module }, SystemBusImpl(), trace, javax.inject.Provider { manager })
        try {
            check(db.automationDao().getById("isolated-dispatch") != null)
            manager.initializeAll()
            val execution = AlarmWorkExecution(ModuleInitializationGate().apply { complete() },
                javax.inject.Provider { dispatcher }, trace)
            val result = AutomationWorker(context, params, execution).doWork()
            val row = requireNotNull(db.automationDao().getById("isolated-dispatch"))
            check(result == ListenableWorker.Result.success() && calls == 1 && row.runCount == 1)
            check(row.nextRunAt > System.currentTimeMillis())
            File(context.filesDir, RECEIPT).writeText(JSONObject().put("seedPid", marker.getInt("pid"))
                .put("pid", android.os.Process.myPid()).put("uuid", params.id.toString())
                .put("calls", calls).put("runCount", row.runCount).put("nextRunAt", row.nextRunAt).toString())
            return result
        } finally {
            manager.destroyAll(); runtime.stopLoop(); worker.cancel(); delay(100)
            db.close(); context.deleteSharedPreferences(preferenceName)
        }
    }
    companion object {
        const val DB = "a8_cold_plan_fixture.db"
        const val READY = "a8-cold-plan-ready.json"
        const val RECEIPT = "a8-cold-plan-receipt.json"
        const val WORK = "a8-isolated-cold-plan"
    }
}
