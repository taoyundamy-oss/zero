package com.zero.v2.app.ui.module

import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.ModuleConfigBase
import com.zero.v2.core.modulemanager.ModuleConfigParser
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties

/** Build a detached candidate; invalid drafts cannot mutate the running module. */
internal fun moduleConfigCandidate(live: ModuleConfigBase, changes: Map<String, String>): String {
    val candidate = live.defaultConfig()
    require(candidate !== live)
    candidate.fromJson(live.toJson())
    val fields = ModuleConfigParser.parse(candidate).associateBy { it.key }
    val properties = candidate::class.declaredMemberProperties.associateBy { it.name }
    for ((key, text) in changes) {
        val field = requireNotNull(fields[key])
        val property = requireNotNull(properties[key])
        val converted = when (property.returnType.classifier) {
            Int::class -> {
                val number = text.toDouble()
                require(number.isFinite() && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
                require(field.fieldType == ConfigFieldType.SLIDER || number == number.toInt().toDouble())
                number.coerceIn(field.min, field.max).toInt()
            }
            Float::class -> {
                val number = text.toDouble()
                require(number.isFinite())
                number.coerceIn(field.min, field.max).toFloat().also { require(it.isFinite()) }
            }
            Boolean::class -> requireNotNull(text.toBooleanStrictOrNull())
            else -> text
        }
        @Suppress("UNCHECKED_CAST")
        (property as KMutableProperty1<Any, Any?>).setter.call(candidate, converted)
    }
    return candidate.toJson()
}
