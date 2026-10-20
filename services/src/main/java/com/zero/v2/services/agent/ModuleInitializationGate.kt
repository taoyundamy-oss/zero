package com.zero.v2.services.agent

import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

/** One application initialization signal shared by foreground and persisted task consumers. */
@Singleton
class ModuleInitializationGate @Inject constructor() {
    private val ready = CompletableDeferred<Unit>()
    suspend fun awaitReady() = ready.await()
    fun complete() { ready.complete(Unit) }
    fun fail(error: Throwable) { ready.completeExceptionally(error) }
}
