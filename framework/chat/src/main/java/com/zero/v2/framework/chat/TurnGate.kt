package com.zero.v2.framework.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TurnGate {
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
    fun <T> stream(prepare: suspend () -> Flow<T>): Flow<T> = flow { exclusive { emitAll(prepare()) } }
}
