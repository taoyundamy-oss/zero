package com.zero.v2.app

import android.app.Application
import android.util.Log
import com.zero.v2.core.modulemanager.ModuleLifecycleManager
import com.zero.v2.services.agent.AgentRuntime
import com.zero.v2.app.background.initializeAutonomousRuntime
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.zero.v2.services.agent.ModuleInitializationGate
import androidx.work.Configuration
import androidx.work.WorkManager
import com.zero.v2.app.di.AlarmWorkerFactoryEntryPoint
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject

@HiltAndroidApp
class ZeroApplication : Application(), Configuration.Provider {

    @Inject lateinit var moduleManager: ModuleLifecycleManager
    @Inject lateinit var agentRuntime: AgentRuntime
    private val initialization: ModuleInitializationGate
        get() = EntryPointAccessors.fromApplication(this, AlarmWorkerFactoryEntryPoint::class.java).initializationGate()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    suspend fun awaitModuleInitialization() = initialization.awaitReady()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(
            EntryPointAccessors.fromApplication(this, AlarmWorkerFactoryEntryPoint::class.java).workerFactory(),
        ).build()

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this)
        scope.launch {
            try {
                initializeAutonomousRuntime(moduleManager, agentRuntime)
                initialization.complete()
                Log.i("ZeroV2", "All modules initialized (${moduleManager.getAll().size} modules)")
            } catch (e: Exception) {
                initialization.fail(e)
                Log.e("ZeroV2", "Module init failed", e)
            }
        }
    }
}
