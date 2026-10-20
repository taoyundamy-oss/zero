package com.zero.v2.core.common

import java.security.MessageDigest

/**
 * 亲密度离散档位 — P1（设计§6 / D4：离散档位，非伪精确"好感 73.42"）。
 *
 * 无记录 = [ACQUAINTANCE]（默认中性，便于迁移——旧角色无状态行视为默认值）。
 * 不影响权限/安全/事实判断/工具调用/系统角色底线（D1）；仅影响表达风格（P3）。
 */
enum class AffinityBand(val label: String) {
    ACQUAINTANCE("初识"),
    WARM("熟络"),
    CLOSE("亲近"),
    DEVOTED("深契"),
    ;

    companion object {
        val DEFAULT: AffinityBand = ACQUAINTANCE
        fun fromName(s: String?): AffinityBand = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}

/**
 * 会话情绪 — P1（设计§6：neutral/warm/concerned/cheerful，短时衰减到 neutral）。
 *
 * 无记录 = [NEUTRAL]（默认中性）。按会话短期保存（D4：情绪按会话，与亲密度按角色分表）。
 */
enum class Mood(val label: String) {
    NEUTRAL("平静"),
    WARM("温和"),
    CONCERNED("关切"),
    CHEERFUL("欢快"),
    ;

    companion object {
        val DEFAULT: Mood = NEUTRAL
        fun fromName(s: String?): Mood = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}

/**
 * 状态事件幂等键计算 — P1（设计§6：限量 CharacterStateEvent 带幂等键）。
 *
 * 同一来源消息 + 同一变更类型 → 同键（IGNORE 幂等，防重复写入）。
 * 与 [com.zero.v2.core.data.entity.MemoryExtractionJobKey] 同律（SHA-256 前 16 hex）。
 */
object CharacterStateEventKey {
    private val HEX = "0123456789abcdef"

    /** 幂等键：changeType + ":" + SHA-256(sourceMessageId) 前 16 hex。sourceMessageId 空 → 用 reason 参与 hash。 */
    fun compute(changeType: String, sourceMessageId: String?, reason: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = (sourceMessageId ?: "") + "\u0001" + reason
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) { val v = b.toInt() and 0xff; sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0x0f]) }
        return "$changeType:${sb.substring(0, 16)}"
    }
}
