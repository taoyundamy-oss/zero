package com.zero.v2.core.moduleapi

import org.json.JSONObject

/**
 * 配置迁移单步 — D8（ConfigMigration 链式迁移）。
 *
 * 每个 Config 子类声明自己的迁移链（fromVersion → toVersion，JSON → JSON），
 * 旧版本持久化 JSON 在恢复前由 [ConfigMigrator] 升序应用直至最新版本。
 *
 * 版本约定: JSON 内 `__v` 字段（缺省 = 1，即 v1 时代无版本戳的存量）。
 * fromJson 的字段默认值兜底依然保留 — 迁移链负责"显式升级 + 版本戳"，双保险。
 */
interface ConfigMigration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(json: String): String
}

/** 迁移执行结果 */
data class MigrateResult(val json: String, val fromVersion: Int, val toVersion: Int) {
    val changed: Boolean get() = fromVersion != toVersion
}

/**
 * 链式迁移执行器: 按 fromVersion 升序应用，每步落 `__v` 戳。
 * 无匹配步骤（已是最新/未知版本）原样返回。
 */
object ConfigMigrator {

    const val VERSION_KEY = "__v"

    fun detectVersion(json: String): Int =
        try { JSONObject(json).optInt(VERSION_KEY, 1) } catch (_: Exception) { 1 }

    fun migrate(json: String, chain: List<ConfigMigration>): MigrateResult {
        val from = detectVersion(json)
        var current = json
        var version = from
        chain.sortedBy { it.fromVersion }.forEach { step ->
            if (version == step.fromVersion) {
                current = stamp(step.migrate(current), step.toVersion)
                version = step.toVersion
            }
        }
        return MigrateResult(current, from, version)
    }

    private fun stamp(json: String, version: Int): String = try {
        JSONObject(json).put(VERSION_KEY, version).toString()
    } catch (_: Exception) { json }
}
