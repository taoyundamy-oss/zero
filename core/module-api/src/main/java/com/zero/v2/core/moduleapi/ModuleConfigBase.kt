package com.zero.v2.core.moduleapi

/**
 * 模块配置基类。
 *
 * 对标 Alife 的 IConfigurable<T> 模式。
 * 每个模块定义自己的 Config 类（继承此基类），
 * ModuleLifecycleManager 在 onAwake() 前自动加载持久化配置。
 *
 * 配置持久化：
 *   使用 Room 表 module_configs(moduleId TEXT PK, configJson TEXT)
 *   toJson() / fromJson() 实现序列化/反序列化。
 *
 * AutoFormUI：
 *   子类中的属性使用 @ConfigField 注解标记，
 *   ModuleConfigParser 在 onAwake() 时一次性解析为 List<FormFieldState>，
 *   Compose UI 不含反射调用。
 */
abstract class ModuleConfigBase {

    /** 模块是否启用（用户可在设置中切换） */
    open var enabled: Boolean = true

    /**
     * 当前配置版本（v5.5，D8）。
     * 存量 JSON 无 `__v` = 版本 1；有迁移链的子类 bump 此值并同步在 toJson 写入 `__v`。
     */
    open val configVersion: Int get() = 1

    /** 迁移链（升序 fromVersion；默认空 = 无需迁移） */
    open val migrations: List<ConfigMigration> get() = emptyList()

    /** 对持久化 JSON 应用迁移链，返回结果与版本变化 */
    fun migrateJson(json: String): MigrateResult =
        ConfigMigrator.migrate(json, migrations)

    /**
     * 序列化配置为 JSON 字符串。
     * 默认使用简单的属性拼接。子类可 override 使用 kotlinx.serialization。
     */
    abstract fun toJson(): String

    /**
     * 从 JSON 字符串反序列化配置。
     */
    abstract fun fromJson(json: String)

    /**
     * 创建默认配置实例。
     * 当持久化存储中无此模块配置时使用。
     */
    abstract fun defaultConfig(): ModuleConfigBase
}
