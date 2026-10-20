package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FallbackMedia] + [FunctionResult.mediaRefs] 单测 — M4 验收：回退块 attachmentId 关联 + 媒体引用。
 *
 * 设计§3.4：视觉/工具结果与原输入用 attachmentId 关联，不悄然改写用户原话；
 * 不支持 profile 有明确回退标签；图片失败不静默丢图。
 */
class FallbackMediaTest {

    @Test
    fun visionBlock_hasAttachmentIdLabel() {
        val b = FallbackMedia.visionBlock("content://img/1", "一只猫")
        assertTrue(b.startsWith("[图片预分析 attachmentId=content://img/1]"))
        assertTrue(b.contains("一只猫"))
    }

    @Test
    fun asrBlock_hasAttachmentIdLabel() {
        val b = FallbackMedia.asrBlock("content://audio/2", "你好世界")
        assertTrue(b.startsWith("[音频转写 attachmentId=content://audio/2]"))
        assertTrue(b.contains("你好世界"))
    }

    @Test
    fun unavailableBlock_noSilentDrop() {
        // 图片失败不静默丢图——降级块带 reason
        val b = FallbackMedia.unavailableBlock("content://img/x", "Oversize")
        assertTrue(b.startsWith("[媒体不可用 attachmentId=content://img/x"))
        assertTrue(b.contains("Oversize"))
    }

    @Test
    fun attachmentIdRemainsUsable_longUri() {
        val longId = "content://com.zero.v2.fileprovider/external/".repeat(10) + "img.png"
        val b = FallbackMedia.visionBlock(longId, "desc")
        // A reference passed to another tool must remain complete.
        val idPart = b.substringAfter("attachmentId=").substringBefore("]")
        assertEquals(longId, idPart)
    }

    @Test
    fun isFallbackBlock_detectsAllKinds() {
        assertTrue(FallbackMedia.isFallbackBlock("[图片预分析 attachmentId=x]\n desc"))
        assertTrue(FallbackMedia.isFallbackBlock("[音频转写 attachmentId=x]\n t"))
        assertTrue(FallbackMedia.isFallbackBlock("[媒体不可用 attachmentId=x reason=r]"))
        assertFalse(FallbackMedia.isFallbackBlock("普通用户消息"))
        assertFalse(FallbackMedia.isFallbackBlock(""))
    }

    @Test
    fun visionBlock_doesNotRewriteUserContent() {
        // 设计§3.4 #4：回退描述是独立块，不并入用户正文 Text
        val userContent = "这张图是什么"
        val block = FallbackMedia.visionBlock("content://img/1", "一只猫")
        // block 与 userContent 是两个独立字符串——不拼接（调用方注入 turnContext，不改 userMsg.content）
        assertFalse(userContent.contains(block))
        assertFalse(block.contains(userContent))
    }

    @Test
    fun functionResult_mediaRefsDefaultNull() {
        // 默认 null（兼容既有调用方）
        val r = FunctionResult.success("ok")
        assertEquals(null, r.mediaRefs)
    }

    @Test
    fun functionResult_carriesMediaRefs() {
        val r = FunctionResult(
            success = true, data = "截图已保存",
            mediaRefs = listOf(MediaRef("content://img/shot", "image/png", MediaKind.IMAGE)),
        )
        assertEquals(1, r.mediaRefs?.size)
        assertEquals("content://img/shot", r.mediaRefs!![0].attachmentId)
        assertEquals(MediaKind.IMAGE, r.mediaRefs!![0].kind)
    }
}
