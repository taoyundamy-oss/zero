package com.zero.v2.services.agent

/** 自主回合触发优先级与顺延分类策略。 */
internal object AutonomousTriggerPolicy {
    enum class TurnType { TASK, INTERACTIVE, TICK }

    fun turnType(
        source: String,
        payload: String,
    ): TurnType =
        when {
            source == "SELF_DRIVE" && payload.isBlank() -> TurnType.TICK
            source == "SELF_DRIVE" || source == "AUTOMATION" || source == "SCHEDULED_PLAYBACK" -> TurnType.TASK
            else -> TurnType.INTERACTIVE
        }

    fun isTick(
        source: String,
        payload: String,
    ): Boolean = source == "SELF_DRIVE" && payload.isBlank()

    fun countsAsRealEvent(
        source: String,
        payload: String,
    ): Boolean =
        when {
            source.startsWith("BATTERY_") -> false
            source == "SCHEDULED_PLAYBACK" -> false
            isTick(source, payload) -> false
            else -> true
        }
}
