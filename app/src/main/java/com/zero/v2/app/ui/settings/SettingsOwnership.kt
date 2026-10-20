package com.zero.v2.app.ui.settings

import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.modulemanager.ModuleConfigParser

/** UI ownership only: persisted field names and configuration owners do not change. */
object SettingsOwnership {
    fun dedicatedFields(moduleId: String): Set<String> = when (moduleId) {
        "agent_runtime" -> setOf("baseIntervalSec", "randomOffsetSec", "intervalMultiplier",
            "maxDoublings", "userPresenceWindowMin")
        "memory" -> setOf("enableReflection", "reflectionHour", "graphExtractionThreshold")
        else -> emptySet()
    }

    fun numericRanges(config: ModuleConfigBase): Map<String, ClosedFloatingPointRange<Float>> =
        ModuleConfigParser.parse(config).associate { it.key to (it.min.toFloat()..it.max.toFloat()) }
}
