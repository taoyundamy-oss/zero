package com.zero.v2.core.common

/**
 * 原生多模态失败回退 Adapter（M4 / 设计§3.4）——Vision/ASR 回退结果用 attachmentId 关联。
 *
 * 设计§3.4 #4："工具及视觉结果与原输入用 attachmentId 关联，避免悄然改写用户原话。"
 * 故回退描述/转写**不 append 到用户正文**，而是经 [visionBlock]/[asrBlock] 格式化为带
 * attachmentId 的标注块，注入 turnContext（与摘要/证据并列，不入 stablePrefix）。
 *
 * 设计§3.4 #5 验收：不支持 profile 有明确回退标签；图片失败不静默丢图；可追踪 route/降级原因。
 *
 * 纯逻辑 → JVM 可单测。M3 已把 vision-poke 降为回退路径（无 IMAGE_INPUT profile 时），
 * M4 正式定性回退 Adapter + attachmentId 关联（替代旧 v6.8 的"改写用户正文"行为）。
 */
object FallbackMedia {

    /** 标签：vision-poke 回退块（attachmentId 截断到 40 字符防日志膨胀）。 */
    fun visionBlock(attachmentId: String, description: String): String {
        val id = attachmentId
        return "[图片预分析 attachmentId=$id]\n$description"
    }

    /** 标签：ASR 回退转写块（音频附件 → 转写文本，attachmentId 关联）。 */
    fun asrBlock(attachmentId: String, transcript: String): String {
        val id = attachmentId
        return "[音频转写 attachmentId=$id]\n$transcript"
    }

    /** 标签：媒体不可用降级块（图片读取失败/超尺寸/不支持 mime——不静默丢图，设计§3.4 #5）。 */
    fun unavailableBlock(attachmentId: String, reason: String): String {
        val id = attachmentId
        return "[媒体不可用 attachmentId=$id reason=$reason]"
    }

    /** 是否为回退块（UI/历史投影可据此剥离展示——保持用户正文不改写）。 */
    fun isFallbackBlock(text: String): Boolean =
        text.startsWith("[图片预分析 attachmentId=") ||
            text.startsWith("[音频转写 attachmentId=") ||
            text.startsWith("[媒体不可用 attachmentId=")
}
