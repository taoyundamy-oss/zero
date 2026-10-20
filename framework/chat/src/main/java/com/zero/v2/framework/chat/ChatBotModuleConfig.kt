package com.zero.v2.framework.chat

import com.zero.v2.core.moduleapi.ConfigField
import com.zero.v2.core.moduleapi.ConfigFieldType
import com.zero.v2.core.moduleapi.ModuleConfigBase
import org.json.JSONObject

/**
 * 对话引擎配置 — 开发前规范化 N0.1（F1，缺陷修复见开发前规范化设计 §五）。
 *
 * FC 循环轮次上限用户可调（D17 用户主权）：
 * 分页读大文件每页 1 轮（read_work_file offset），旧硬编码 5 轮会中途截断多页读取，
 * 且退出时无最终综合轮 → 对话界面"中断回复"（根因：ChatBotModule FC 循环 5 轮硬上限）。
 * 默认 16 约可连读 250KB 文档（pageLength 8000 字/页）。
 */
class ChatBotModuleConfig : ModuleConfigBase() {

    @ConfigField(
        label = "FC 最大轮次", fieldType = ConfigFieldType.NUMBER,
        min = 6.0, max = 100.0,
        description = "单次对话最多工具调用轮数（分页读文件每页 1 轮）。默认 16 约可连读 250KB 文档；" +
            "上限 100（2026-09-18 用户反馈放宽：动态模块编译链 develop_module→修复→挂载→调用 30 轮不够用）；" +
            "达上限后 Zero 会基于已读内容作答，不会中断"
    )
    var maxFcRounds: Int = 16

    override fun toJson(): String = """{"maxFcRounds":$maxFcRounds}"""

    override fun fromJson(json: String) {
        try {
            val obj = JSONObject(json)
            // coerceIn 兜底：防直改存储 JSON 绕过 UI 界限（N4.1 通用钳位落地前的字段级防护）
            maxFcRounds = obj.optInt("maxFcRounds", 16).coerceIn(6, 100)
        } catch (_: Exception) {}
    }

    override fun defaultConfig(): ModuleConfigBase = ChatBotModuleConfig()
}
