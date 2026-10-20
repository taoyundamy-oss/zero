package com.zero.v2.core.moduleapi

/** Returns after durable handoff commits, without running business work in a broadcast. */
interface AlarmWorkQueue {
    suspend fun enqueue(action: String)
}
