package com.zero.v2.app.di

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.WorkManager
import com.zero.v2.app.background.DurableAlarmWorkQueue
import com.zero.v2.core.moduleapi.AlarmWorkQueue
import com.zero.v2.services.agent.ModuleInitializationGate
import dagger.Binds
import dagger.hilt.EntryPoint
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmWorkModule {
    @Binds abstract fun bindAlarmWorkQueue(queue: DurableAlarmWorkQueue): AlarmWorkQueue

    companion object {
        @Provides @Singleton
        fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
    }
}

/** Supports configuration before Application field injection in isolated instrumentation. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmWorkerFactoryEntryPoint {
    fun workerFactory(): HiltWorkerFactory
    fun initializationGate(): ModuleInitializationGate
}
