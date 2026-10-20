package com.zero.v2.services.module

/** No queue/replay for duplicate triggers; unrelated task IDs have independent ownership. */
internal class AutomationClaims {
    private val active = mutableSetOf<String>()

    suspend fun <T : Any> run(id: String, action: suspend () -> T): T? {
        if (!synchronized(active) { active.add(id) }) return null
        return try { action() } finally { synchronized(active) { active.remove(id) } }
    }
}

internal fun ExecutionPlan.validationError(): String? {
    val ids = steps.map { it.index }
    return when {
        steps.isEmpty() -> "计划为空"
        ids.any { it <= 0 } || ids.toSet().size != ids.size -> "步骤标识必须为唯一正整数"
        steps.any { it.index in it.dependsOn } -> "步骤不能依赖自身"
        steps.any { step -> step.dependsOn.any { it !in ids } } -> "依赖引用不存在"
        else -> cycleError()
    }
}

private fun ExecutionPlan.cycleError(): String? {
    val pending = steps.toMutableList()
    val complete = mutableSetOf<Int>()
    while (pending.isNotEmpty()) {
        val ready = pending.filter { complete.containsAll(it.dependsOn) }
        if (ready.isEmpty()) return "计划存在循环依赖"
        complete.addAll(ready.map { it.index })
        pending.removeAll(ready.toSet())
    }
    return null
}
