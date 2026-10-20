package com.zero.v2.framework.memory

import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigMigration
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.ModuleConfigBase
import org.json.JSONObject

/**
 * 记忆模块配置 — Step 4.8 创建，5.2 补全为 §3.4 完整版（DESIGN §1.3 用户主权）。
 * 所有字段均有真实消费端（ReflectionJob / GraphExtractionJob / HistoryCompressor）。
 *
 * KB0.2（KA11 · 用户裁定 2026-09-13）：reflectionModel/graphExtractionModel 两字段已移除——
 * 后台 LLM 一律跟随当前对话模型 select(CHAT)（原 DROPDOWN 静态名单硬编码，模型名随
 * profile 演进即死名单）；configVersion 保持 2（删字段对存量 JSON 无破坏，C7）。
 */
class MemoryModuleConfig : ModuleConfigBase() {

    @ConfigField(
        label = "每日 Reflection 开关", fieldType = ConfigFieldType.TOGGLE,
        description = "开启后 Agent 每天在设定时间做记忆反思：冲突检测/过期归档/压缩/AI 知识提炼（消耗少量 Token）"
    )
    var enableReflection: Boolean = false

    @ConfigField(
        label = "Reflection 时间(时, 0-23)", fieldType = ConfigFieldType.NUMBER,
        min = 0.0, max = 23.0,
        description = "每日反思执行的小时（默认凌晨 2 点低负载时段）"
    )
    var reflectionHour: Int = 2

    @ConfigField(
        label = "冲突检测频率", fieldType = ConfigFieldType.DROPDOWN,
        options = ["每天", "每周", "每月", "手动"],
        description = "多频繁在反思中检查记忆矛盾（手动 = 仅不自动检测）"
    )
    var conflictDetectionFrequency: String = "每周"

    @ConfigField(
        label = "图谱提取触发阈值", fieldType = ConfigFieldType.NUMBER,
        min = 10.0, max = 2000.0,
        description = "累积多少条新记忆后触发一次用户图谱提取（LLM 提取实体/属性/关系）；<10 烧 Token，>2000 图谱陈旧"
    )
    var graphExtractionThreshold: Int = 50

    @ConfigField(
        label = "过期归档间隔(天)", fieldType = ConfigFieldType.NUMBER,
        min = 7.0, max = 3650.0,
        description = "多少天未访问的 ACTIVE 记忆在反思时归档"
    )
    var archiveAfterDays: Int = 90

    @ConfigField(
        label = "LLM 压缩触发阈值", fieldType = ConfigFieldType.NUMBER,
        min = 20.0, max = 500.0,
        description = "记忆总量达到多少条后触发一次 LLM 分层压缩（20 条/批）"
    )
    var compressionThreshold: Int = 100

    override fun toJson(): String {
        return """{"__v":$configVersion,"enableReflection":$enableReflection,"reflectionHour":$reflectionHour,""" +
                """"conflictDetectionFrequency":"$conflictDetectionFrequency",""" +
                """"graphExtractionThreshold":$graphExtractionThreshold,""" +
                """"archiveAfterDays":$archiveAfterDays,"compressionThreshold":$compressionThreshold}"""
    }

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            enableReflection = obj.optBoolean("enableReflection", false)
            // N4.2：钳位兜底（§7.3——D51 恢复路径直读 JSON，须与 UI 同界）
            reflectionHour = obj.optInt("reflectionHour", 2).coerceIn(0, 23)
            conflictDetectionFrequency = obj.optString("conflictDetectionFrequency", "每周")
            graphExtractionThreshold = obj.optInt("graphExtractionThreshold", 50).coerceIn(10, 2000)
            archiveAfterDays = obj.optInt("archiveAfterDays", 90).coerceIn(7, 3650)
            compressionThreshold = obj.optInt("compressionThreshold", 100).coerceIn(20, 500)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = MemoryModuleConfig()

    // ═══ v5.5 (D8): 版本声明与迁移链 ═══

    /** v2 = 5.2 补全 §3.4 五字段（v1 存量: enableReflection/reflectionHour/graphExtractionThreshold） */
    override val configVersion: Int get() = 2

    override val migrations: List<ConfigMigration> = listOf(MemoryV1ToV2)
}

/** v1 → v2: 为 v1 存量 JSON 显式补入 5.2 新字段默认值（fromJson 兜底之外的双保险）
 *  KB0.2：reflectionModel/graphExtractionModel 两 put 已随字段移除（KA11）——
 *  v1 存量 JSON 中残留的旧 key 静默保留无害（fromJson 不再读取） */
private object MemoryV1ToV2 : ConfigMigration {
    override val fromVersion = 1
    override val toVersion = 2
    override fun migrate(json: String): String = try {
        JSONObject(json).apply {
            put("conflictDetectionFrequency", optString("conflictDetectionFrequency", "每周"))
            put("archiveAfterDays", optInt("archiveAfterDays", 90))
            put("compressionThreshold", optInt("compressionThreshold", 100))
        }.toString()
    } catch (_: Exception) { json }
}
