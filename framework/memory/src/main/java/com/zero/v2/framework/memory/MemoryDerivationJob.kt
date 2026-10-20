package com.zero.v2.framework.memory

import com.zero.v2.core.data.store.MemoryDerivationStore
import com.zero.v2.core.moduleapi.ModuleLifecycle
import com.zero.v2.services.agent.AgentJob
import javax.inject.Inject
import javax.inject.Singleton

/** Durable local work only. Classification stays in the main dialogue, not in this worker. */
@Singleton
class MemoryDerivationJob @Inject constructor(
    private val memory: MemoryModule, private val store: MemoryDerivationStore,
) : AgentJob {
    private var next = 0L
    private var cursor = ""
    private var lastFingerprint: String? = null
    override val id = "memory_derivation"
    override val nextRunTime: Long get() =
        if (memory.isEnabled && memory.lifecycle.value == ModuleLifecycle.STARTED) next else Long.MAX_VALUE
    override suspend fun execute() {
        if (!memory.isEnabled || memory.lifecycle.value != ModuleLifecycle.STARTED) return
        next = System.currentTimeMillis() + 30_000
        memory.processPendingDerivations()
        cursor = store.sweep(cursor, System.currentTimeMillis())
        if (cursor.isEmpty()) {
            val current = store.viewFingerprint()
            if (current != lastFingerprint) {
                lastFingerprint = current
                memory.onDerivationViewsRebuilt()
            }
        }
    }
}
