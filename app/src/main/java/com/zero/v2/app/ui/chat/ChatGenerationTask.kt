package com.zero.v2.app.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One UI-owned collector; cancellation must finish cleanup before another collector starts. */
internal class ChatGenerationTask(private val scope: CoroutineScope) {
    private var current: Job? = null
    private val transitionMutex = Mutex()
    private var pendingTransitions = 0
    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning = _isTransitioning.asStateFlow()
    var userStopped: Boolean = false
        private set

    fun start(block: suspend () -> Unit): Boolean {
        if (pendingTransitions > 0 || current?.isCompleted == false) return false
        userStopped = false
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { block() } finally { current = null }
        }
        current = job
        job.start()
        return true
    }

    fun stop(): Boolean {
        val job = current?.takeIf { it.isActive } ?: return false
        userStopped = true
        job.cancel()
        return true
    }

    /** Reserve immediately on the UI dispatcher, including queued transitions. */
    fun transition(
        shouldTransition: suspend () -> Boolean = { true },
        cancelGeneration: () -> Boolean = { true },
        block: suspend () -> Unit,
    ): Job {
        pendingTransitions++
        _isTransitioning.value = true
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                transitionMutex.withLock {
                    if (!shouldTransition()) return@withLock
                    if (cancelGeneration()) {
                        val generation = current
                        generation?.cancel()
                        generation?.join()
                    }
                    block()
                }
            } finally {
                pendingTransitions--
                _isTransitioning.value = pendingTransitions > 0
            }
        }
    }
}
