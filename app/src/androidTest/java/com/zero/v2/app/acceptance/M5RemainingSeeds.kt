package com.zero.v2.app.acceptance

import com.zero.v2.core.common.*
import com.zero.v2.core.data.entity.*

/** Synthetic preconditions, not model successes. Every group gets a new isolated Room fixture. */
internal object M5RemainingSeeds {
    val preferenceSourceId: String get() = source("preference", "验收茶歇我长期偏好温豆浆。").id
    private fun source(id: String, text: String) = ChronicleSourceEntity.message(
        MessageEntity("m5-seed-$id", "m5-seed-synthetic", "user", text, source = "CHAT_WINDOW", createdAt = 100), 200)!!
    suspend fun prepare(f: ChronicleAgentFixture, group: String) {
        suspend fun seed(id: String, text: String, type: MemoryType, key: String, content: String = text): MemoryDerivationEntity {
            val s = source(id, text); f.db.chronicleSourceDao().retainSource(s)
            val d = MemoryDerivationEntity.create(s.id, content, text, type, "EXPLICIT", "personal", key, .8f, now = 300)
            f.store.submit(d); f.store.drain()
            check(f.db.memoryDao().getById(d.memoryId)?.status == MemoryStatus.ACTIVE)
            return d
        }
        when (group) {
            "other-kinds", "conflict", "task-format" -> Unit
            "update-complete" -> {
                seed("cabinet", "验收档案放在北侧二号柜。", MemoryType.FACT, "验收档案/位置")
                seed("task", "蓝盒备份正在进行，下一步核对校验码，验收条件是两份校验码一致。", MemoryType.TASK, "蓝盒备份/任务",
                    TaskMemory("ACTIVE", "核对校验码", "两份校验码一致", null, "蓝盒备份").encode())
            }
            "withdraw" -> seed("preference", "验收茶歇我长期偏好温豆浆。", MemoryType.PREFERENCE, "验收茶歇/偏好")
            "fresh-context" -> {
                seed("format", "以后盘点报告先列结论，再列清单。", MemoryType.DIRECTIVE, "盘点报告/格式")
                seed("contact", "顾遥是虚构项目的联络人。", MemoryType.RELATIONSHIP, "虚构项目/联络人")
                seed("boundary", "未经确认不要代顾遥承诺。", MemoryType.DIRECTIVE, "顾遥/承诺边界")
                seed("cabinet-current", "验收档案放在西侧四号柜。", MemoryType.FACT, "验收档案/位置")
                seed("task-done", "蓝盒备份已完成，两份校验码一致。", MemoryType.TASK, "蓝盒备份/任务",
                    TaskMemory("DONE", "无需后续动作", "两份校验码一致", null, "蓝盒备份").encode())
                seed("event", "2026年10月17日参加虚构纸鸢工作坊并完成登记。", MemoryType.EVENT, "纸鸢工作坊/活动")
                seed("preference", "验收茶歇我长期偏好温豆浆。", MemoryType.PREFERENCE, "验收茶歇/偏好")
                check(f.store.withdrawMatching("温豆浆", 400) == 1)
            }
            else -> error("unknown_group")
        }
    }
}
