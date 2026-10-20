package com.zero.v2.app.acceptance

import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerAcceptanceEntryPoint {
    fun workerFactory(): HiltWorkerFactory
}
