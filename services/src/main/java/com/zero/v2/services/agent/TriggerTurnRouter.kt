package com.zero.v2.services.agent

import com.zero.v2.services.realtime.ChatEngineBridge
import javax.inject.Inject

/** Owns the trigger-to-turn boundary and exposes the active turn used by notification guards. */
class TriggerTurnRouter @Inject constructor(
    private val job: AutonomousThinkingJob,
    private val chat: ChatEngineBridge,
    private val runtime: AgentRuntime,
) {
    fun activeTurn() = chat.activeTurn()
    fun onTrigger(event: TriggerEvent) = job.onTrigger(event)
    fun kick() = runtime.kick()
    fun close() = job.close()
}
