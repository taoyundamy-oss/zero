package com.zero.v2.services.llm

/**
 * 模型能力枚举 — Zero v2 Phase 1。
 *
 * 用于 ModelSelector 的能力匹配，替代硬编码的 modelId 判断。
 * 每个 ILanguageModel 实现声明自己支持的能力集合。
 */
enum class ModelCapability {
    /** 基础对话（文本回合，所有 chat 模型必备；不等同 IMAGE_INPUT——M2 不把 CHAT 当图像输入） */
    CHAT,

    /** 深度推理 / 思维链（如 deepseek-reasoner） */
    REASONING,

    /** 视觉理解能力（描述/OCR/图表分析）；与 IMAGE_INPUT 区分：本项=能"看懂"，IMAGE_INPUT=能"接收图" */
    VISION,

    /** 接受图片作为原生输入内容块（M2 新增；CHAT 不隐含此项） */
    IMAGE_INPUT,

    /** Native audio input; independent of image capability. */
    AUDIO_INPUT,

    /** Native video input; image support alone does not imply video support. */
    VIDEO_INPUT,

    /** Function Calling / Tool Use */
    FUNCTION_CALLING,

    /** 长上下文（>128K tokens） */
    LONG_CONTEXT,

    /** 快速响应（低延迟，适合简单任务） */
    FAST_RESPONSE
}
