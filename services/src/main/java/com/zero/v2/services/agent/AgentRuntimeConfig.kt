package com.zero.v2.services.agent

import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.ModuleConfigBase
import org.json.JSONObject

/**
 * Agent Runtime 报点配置（DESIGN §2.2 / §1.3 用户主权）。
 *
 * Alife 自适应报点算法:
 *   下次间隔 = (基础间隔 + 随机偏移) × 间隔倍数^min(连续触发次数, 最大翻倍次数)
 *   用户消息步进至顶峰并保持安静；离场后干净报点归零，开始下一波。
 *
 * 默认参数效果（90s/±30s/3x/4x）:
 *   活跃期 ~60-120s → 轻闲 ~3-6 分钟 → 安静 ~9-18 分钟 → 深度休眠 ~27-54 分钟 → 最大 ~1.4-2.7 小时
 *
 * 所有参数通过 @ConfigField 暴露在模块详情页，用户可调（D17 用户主权）。
 */
class AgentRuntimeConfig : ModuleConfigBase() {

    @ConfigField(
        label = "基础间隔(秒)", fieldType = ConfigFieldType.NUMBER,
        min = 30.0, max = 3600.0,
        description = "离场波次的基础间隔；用户对话使间隔逐级延长。默认 90 秒"
    )
    var baseIntervalSec: Int = 90

    @ConfigField(
        label = "随机偏移范围(秒)", fieldType = ConfigFieldType.NUMBER,
        min = 0.0, max = 600.0,
        description = "每次间隔在基准上叠加 ±随机偏移，模拟真人节奏。默认 30 秒；0=关闭抖动"
    )
    var randomOffsetSec: Int = 30

    // 注: Float 而非设计稿的 Double — ModuleDetailViewModel 反射写入仅转换 Float/Int/Boolean（D30）
    @ConfigField(
        label = "间隔倍数", fieldType = ConfigFieldType.NUMBER,
        min = 1.0, max = 10.0,
        description = "用户对话或自主回合使间隔按此倍数增长；顶峰静默，离场后开启下一波。默认 3 倍"
    )
    var intervalMultiplier: Float = 3.0f

    @ConfigField(
        label = "最大翻倍次数", fieldType = ConfigFieldType.NUMBER,
        min = 0.0, max = 8.0,
        description = "指数增长上限。默认 4 次（最大约 1.4-2.7 小时一报）；0=恒定间隔不退避"
    )
    var maxDoublings: Int = 4

    // S2R.8（v6 · R15 裁定⑥）：在场静默保险窗——仅作保险开关（事件探测已实质实现在场
    // 判定：用户消息/IM 等实质事件本身就会步进安静+顺延归零）；0=禁用（默认，裁定）
    @ConfigField(
        label = "在场静默窗(分钟)", fieldType = ConfigFieldType.NUMBER,
        min = 0.0, max = 120.0,
        description = "最近用户消息 N 分钟内报点不开自驱回合（保险项）。默认 0=禁用——事件顺延已是主判定"
    )
    var userPresenceWindowMin: Int = 0

    override fun toJson(): String {
        return """{"baseIntervalSec":$baseIntervalSec,"randomOffsetSec":$randomOffsetSec,""" +
                """"intervalMultiplier":$intervalMultiplier,"maxDoublings":$maxDoublings,""" +
                """"userPresenceWindowMin":$userPresenceWindowMin}"""
    }

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            // N4.2：钳位兜底（§7.3——D51 恢复路径直读 JSON，须与 UI 同界）
            baseIntervalSec = obj.optInt("baseIntervalSec", 90).coerceIn(30, 3600)
            randomOffsetSec = obj.optInt("randomOffsetSec", 30).coerceIn(0, 600)
            intervalMultiplier = obj.optDouble("intervalMultiplier", 3.0).toFloat().coerceIn(1.0f, 10.0f)
            maxDoublings = obj.optInt("maxDoublings", 4).coerceIn(0, 8)
            userPresenceWindowMin = obj.optInt("userPresenceWindowMin", 0).coerceIn(0, 120)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = AgentRuntimeConfig()
}
