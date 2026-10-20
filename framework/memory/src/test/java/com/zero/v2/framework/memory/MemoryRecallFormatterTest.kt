package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallFormatterTest {
    @Test fun longOriginalAndCompleteQualifiersAreNeverSummarized() {
        val text = "只有经本人确认才执行；不得自动删除。" + "较长的独立来源内容。".repeat(120)
        val memory = MemoryEntity("m", "须先确认，不删除", type = MemoryType.DIRECTIVE, origin = "MODEL_CLASSIFIED", createdAt = 1)
        val payload = JSONObject().put("sourceBody", text).put("quote", "不得自动删除。")
            .put("sourceId", "source").put("currentness", "STRUCTURAL_CANDIDATE").put("basis", "EXPLICIT")
            .put("recordedAt", 1).put("claimKey", "文件/操作").toString()
        val result = MemoryRecallFormatter.format(memory, payload)
        assertTrue(result.contains(text)); assertTrue(result.contains("不得自动删除。"))
        assertTrue(result.contains("STRUCTURAL_CANDIDATE")); assertTrue(result.contains("source_id=source"))
        assertTrue(result.length > 400)
    }
    @Test fun unknownLegacyHasTimesWithoutGainingSourceQualification() {
        val result = MemoryRecallFormatter.format(MemoryEntity("old", "旧内容", createdAt = 100), null)
        assertTrue(result.contains("历史笔记未核验")); assertTrue(result.contains("记忆记录="))
        assertTrue(result.contains("发生=未知")); assertFalse(result.contains("source_id="))
    }
}
