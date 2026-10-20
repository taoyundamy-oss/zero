package com.zero.v2.app.ui.settings

import com.zero.v2.app.ui.date.WheelDateValues
import com.zero.v2.core.common.MemoryType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MemoryReviewDateTest {
    private val task = TaskReviewFields("ACTIVE", "检查备份", "校验通过", "")
    @Test fun secondsPrecisionFromUserDoesNotBlockProposalWithoutTextParsing() {
        val at = WheelDateValues.epoch(LocalDateTime.of(2026, 9, 20, 20, 15, 9), ZoneId.of("Asia/Shanghai"))
        val form = MemoryReviewForm("合成：仅私聊回复", MemoryType.DIRECTIVE, "personal", "本人确认", at, at, null)
        assertTrue(form.errors(task).isEmpty())
        assertEquals(0L, at % 1000)
    }
    @Test fun unknownDatesRemainUnknownAndRangeErrorNamesField() {
        val form = MemoryReviewForm("合成", MemoryType.FACT, "personal", "确认", null, null, null)
        assertTrue(form.errors(task).isEmpty())
        assertNull(form.occurred)
        assertTrue(form.copy(from = 100, until = 100).errors(task).single().startsWith("失效时间"))
        assertTrue(form.copy(from = 100, until = 99).errors(task).single().startsWith("失效时间"))
    }
    @Test fun missingFieldsAreActionableAndTaskEncodingMatchesStorageLimits() {
        val form = MemoryReviewForm("合成", MemoryType.TASK, "", "", null, null, null)
        val errors = form.errors(task.copy(next = "", acceptance = ""))
        assertTrue(errors.any { it.startsWith("范围") })
        assertTrue(errors.any { it.startsWith("复核理由") })
        assertTrue(errors.any { it.startsWith("下一步") })
        assertTrue(errors.any { it.startsWith("验收条件") })
        assertTrue(form.copy(content = "字".repeat(4000)).errors(task).any { it.contains("含任务字段") })
        assertTrue(form.errors(task.copy(next = "第一步\n第二步")).any { it.contains("单行") })
    }
}
