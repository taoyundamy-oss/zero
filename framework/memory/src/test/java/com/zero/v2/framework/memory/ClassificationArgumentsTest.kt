package com.zero.v2.framework.memory

import com.zero.v2.core.common.MemoryType
import org.junit.Assert.*
import org.junit.Test

class ClassificationArgumentsTest {
    private val args = mapOf<String, Any?>("source_id" to "a".repeat(64), "content" to "清淡", "quote" to "喜欢清淡",
        "type" to "PREFERENCE", "basis" to "EXPLICIT", "claim_key" to "饮食")
    @Test fun typeIsChosenByCallerAndNeverGuessed() {
        for (type in listOf("FACT", "EVENT", "PREFERENCE", "DIRECTIVE", "RELATIONSHIP")) {
            assertEquals(MemoryType.valueOf(type), ClassificationArguments.parse(args + ("type" to type), 10).type)
        }
    }
    @Test fun missingAndUnknownTypesAreRejected() {
        for (input in listOf(args - "type", args + ("type" to "unknown"), args - "basis", args - "source_id")) {
            try { ClassificationArguments.parse(input, 10); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun inferredDirectiveCannotBecomeExplicitNorm() {
        try { ClassificationArguments.parse(args + mapOf("type" to "DIRECTIVE", "basis" to "INFERRED"), 10); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun datesNeverDefaultToRecordingTime() {
        val d = ClassificationArguments.parse(args, 10)
        assertNull(d.occurredAt); assertNull(d.validFrom); assertNull(d.validUntil)
        for (time in listOf(-1, 1.5, "123", Double.NaN, Double.POSITIVE_INFINITY)) {
            try { ClassificationArguments.parse(args + ("occurred_at" to time), 10); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun taskFieldsRequiredAndPreserved() {
        val taskArgs = args + mapOf("type" to "TASK", "task_status" to "ACTIVE", "next_step" to "检查", "acceptance" to "一致")
        val entry = ClassificationArguments.parse(taskArgs, 10)
        assertEquals(MemoryType.TASK, entry.type)
        assertEquals("ACTIVE", com.zero.v2.core.common.TaskMemory.decode(entry.content)!!.status)
        for (key in listOf("task_status", "next_step", "acceptance")) {
            try { ClassificationArguments.parse(taskArgs - key, 10); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun internalTaskHeadersCannotBeWrappedAsTaskBody() {
        val task = args + mapOf("type" to "TASK", "task_status" to "DONE", "next_step" to "无需后续", "acceptance" to "一致")
        for (content in listOf("[zero-task:v1 status=DONE]\n下一步：无需后续", "  [zero-task:v1 status=DONE]\\n下一步：无需后续")) {
            try { ClassificationArguments.parse(task + ("content" to content), 10); fail("duplicate protocol accepted") }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("task_content_must_be_plain_text")) }
        }
    }
    @Test fun ordinaryTaskTextAndOtherTypesAreNotRewritten() {
        val content = "检查文件中的字面量\\n并完成核对"
        val task = args + mapOf("type" to "TASK", "task_status" to "DONE", "next_step" to "无需后续", "acceptance" to "一致", "content" to content)
        assertEquals(content, com.zero.v2.core.common.TaskMemory.decode(ClassificationArguments.parse(task, 10).content)!!.text)
        val note = "[zero-task:v1 status=DONE]只是协议示例"
        assertEquals(note, ClassificationArguments.parse(args + ("content" to note), 10).content)
    }
}
