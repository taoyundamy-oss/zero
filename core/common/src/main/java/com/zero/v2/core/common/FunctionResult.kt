package com.zero.v2.core.common

/**
 * 函数执行结果 — F2 从 core:module-manager 迁入 core:common。
 *
 * 迁移原因（L-3 / 迭代开发设计§2.1）：FunctionResult 是跨层共用的窄契约，
 * 原定义于 module-manager 致使 services/framework 依赖 module-manager 具体实现。
 * 迁入 common 后，services/framework 可经 common 引用，为 F2 移除
 * `services → :core:module-manager` Gradle 边做准备（module-manager 内部仍用）。
 *
 * 兼容：纯包路径变更，字段与语义不变；module-manager 旧定义已删，统一经 common 引用。
 */
data class FunctionResult(
    val success: Boolean,
    val data: String = "",
    val structuredData: Any? = null,
    val errorMessage: String? = null,
    val tokensUsed: Long = 0,
    /**
     * M4：可选、限额的媒体结果引用（设计§3.4 #3——不直接把任意二进制塞工具回复）。
     *
     * 工具/视觉结果经 [MediaRef.attachmentId] 与原输入关联（不并入 [data] 文本）。
     * 截图/视觉类工具可返回图片引用供 UI/后续处理；二进制载荷仍不入库/日志/Trace。
     * null=无媒体引用（默认，兼容既有调用方）。
     */
    val mediaRefs: List<MediaRef>? = null,
) {
    companion object {
        fun success(data: String, tokensUsed: Long = 0) =
            FunctionResult(true, data = data, tokensUsed = tokensUsed)
        fun failure(error: String) =
            FunctionResult(false, errorMessage = error)
    }
}

/**
 * 媒体结果引用（M4 / 设计§3.4）——工具/视觉结果与原输入用 attachmentId 关联。
 *
 * 只持引用（attachmentId=content URI 或稳定 id）+ mime + kind，**不持二进制载荷**
 * （设计§3：base64/媒体载荷不入库/日志/Trace）。UI/调用方按引用读取内容。
 */
data class MediaRef(
    val attachmentId: String,
    val mime: String,
    val kind: MediaKind,
)

/** 媒体类型（与 [AttachmentType] 对齐）。 */
enum class MediaKind { IMAGE, AUDIO, FILE, VIDEO }

/** Bounded references, never binary payloads. TOOL history retains ownership and call association. */
fun FunctionResult.attachments(): List<Attachment> = if (!success) emptyList() else mediaRefs.orEmpty()
    .take(8).filter { it.attachmentId.startsWith("file:") || it.attachmentId.startsWith("content:") }
    .map { Attachment(it.attachmentId, it.mime, AttachmentType.valueOf(it.kind.name)) }
