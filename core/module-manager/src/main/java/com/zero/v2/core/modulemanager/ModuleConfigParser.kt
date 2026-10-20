package com.zero.v2.core.modulemanager

import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.ModuleConfigBase
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation

/**
 * 模块配置解析器 — Zero v2.4 ConfigField 统一规范（D30 方案B）。
 *
 * 使用 kotlin-reflect KProperty 原生读取：
 *   - KClass.declaredMemberProperties 遍历所有 Kotlin 属性
 *   - KProperty.findAnnotation<ConfigField>() 读取注解（Kotlin 元数据原生支持）
 *   - getter.call() 读取当前值
 *
 * 规范（D30）：
 *   - 禁止 Java 反射（declaredFields/getDeclaredField/methods）访问 Kotlin property
 *   - 原因：Kotlin PROPERTY 目标注解存储于合成成员（xxx$annotations），
 *     Java 反射无法稳定读写（历史上 4 轮修复均失效）
 */
object ModuleConfigParser {

    data class FormFieldState(
        val key: String,
        val label: String,
        val description: String,
        val fieldType: ConfigFieldType,
        val isPassword: Boolean,
        val currentValue: String,
        val options: List<String>,
        val min: Double,
        val max: Double
    )

    fun parse(config: ModuleConfigBase): List<FormFieldState> {
        return config::class.declaredMemberProperties.mapNotNull { prop ->
            val anno = prop.findAnnotation<ConfigField>() ?: return@mapNotNull null
            val value = try {
                prop.getter.call(config)?.toString() ?: ""
            } catch (_: Exception) {
                ""
            }
            FormFieldState(
                key = prop.name,
                label = anno.label,
                description = anno.description,
                fieldType = anno.fieldType,
                isPassword = anno.password,
                currentValue = value,
                options = anno.options.toList(),
                min = anno.min,
                max = anno.max
            )
        }
    }
}
