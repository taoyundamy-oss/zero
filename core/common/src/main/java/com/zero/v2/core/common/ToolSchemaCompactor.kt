package com.zero.v2.core.common

/**
 * OpenAI tools 参数 schema 的保守紧凑化策略。
 *
 * 只删除空描述和空的非语义扩展字段；name/type/required/enum/properties/items 等
 * 可能参与参数校验的字段始终保留，即使其值为空，避免改变工具调用语义。
 */
object ToolSchemaCompactor {
    private val semanticKeys = setOf("name", "type", "required", "enum", "properties", "items")

    fun compact(schema: Map<String, Any?>): Map<String, Any?> =
        compactMap(schema)

    private fun compactMap(source: Map<*, *>): Map<String, Any?> = buildMap {
        source.forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString() ?: return@forEach
            if (key == "description" && rawValue is String && rawValue.isBlank()) return@forEach
            val value = compactValue(rawValue)
            if (value == null && key !in semanticKeys) return@forEach
            if (value is Map<*, *> && value.isEmpty() && key !in semanticKeys) return@forEach
            if (value is List<*> && value.isEmpty() && key !in semanticKeys) return@forEach
            put(key, value)
        }
    }

    private fun compactValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> compactMap(value)
        is List<*> -> value.map { item -> compactValue(item) }
        else -> value
    }
}
