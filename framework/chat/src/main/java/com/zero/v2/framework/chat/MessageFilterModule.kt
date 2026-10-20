package com.zero.v2.framework.chat

import com.zero.v2.core.bus.IMessageFilter
import com.zero.v2.core.bus.MessagePipeline
import com.zero.v2.core.common.ChatSendEvent
import com.zero.v2.core.moduleapi.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消息过滤器模块。
 *
 * 对标 Alife 的 MessageFilterService — 每条消息预处理。
 * 职责：
 *   1. 注入时间戳（对标 Alife OnChatSend 中的时间前缀）
 *   2. 消息长度截断
 *   3. 行为提示词定期重注入（每 N 条一次）
 *
 * ⚠️ 性能契约（v2.2）：process() 必须在 100ms 内完成。
 *   禁止网络请求、DB 写入、文件 I/O、复杂正则回溯。
 */
@Singleton
class MessageFilterModule @Inject constructor(
    private val messagePipeline: MessagePipeline
) : IZeroModule, IMessageFilter {

    override val metadata = ModuleMetadata(
        id = "message_filter",
        displayName = "消息过滤",
        description = "消息预处理：时间戳注入、长度截断、行为提示重注入",
        category = ModuleCategory.CORE,
        launchOrder = -100  // 第二个启动（ChatBotModule -200 之后）
    )

    private val _lifecycle = MutableStateFlow(ModuleLifecycle.UNINITIALIZED)
    override val lifecycle = _lifecycle.asStateFlow()

    override var isEnabled: Boolean = true
    override val requiredPermissions: List<String> = emptyList()

    /** 配置 — 对标 Alife MessageFilterData */
    var enableTimestamp: Boolean = true
    var maxMessageLength: Int = 8000
    var injectionInterval: Int = 10
    var messageAppend: String = "(注意消息来源和意图；调用工具后等待结果；保持回复简洁；禁止编造结果)"

    override val config = object : ModuleConfigBase() {
        override fun toJson() = "{}"
        override fun fromJson(json: String) {}
        override fun defaultConfig() = this
    }

    // IMessageFilter
    override val filterOrder: Int = -100

    private var injectionCountdown: Int = 0

    override suspend fun onAwake() {
        _lifecycle.value = ModuleLifecycle.AWAKE
    }

    override suspend fun onStart() {
        messagePipeline.registerFilter(this)
        _lifecycle.value = ModuleLifecycle.STARTED
    }

    override suspend fun onDestroy() {
        messagePipeline.unregisterFilter(this)
        _lifecycle.value = ModuleLifecycle.DESTROYED
    }

    override suspend fun onConfigChanged(newConfig: ModuleConfigBase) {}

    override suspend fun checkAvailability(): Boolean = true
    override fun markDisabled() { _lifecycle.value = ModuleLifecycle.DISABLED }

    /**
     * 处理消息。
     *
     * ⚠️ 必须在 100ms 内完成。
     */
    override suspend fun process(event: ChatSendEvent): ChatSendEvent {
        var message = event.message

        // 1. 时间戳注入（对标 Alife MessageFilter OnChatSend）
        if (enableTimestamp) {
            val now = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            message = "当前时间:[${now.format(formatter)}]\n$message"
        }

        // 2. 长度截断
        if (message.length > maxMessageLength) {
            message = message.take(maxMessageLength) +
                "(文本过长，超过 ${maxMessageLength} 的部分已截断)"
        }

        // 3. 行为提示词定期重注入
        if (injectionCountdown == 0) {
            message = "$message\n$messageAppend"
            injectionCountdown = injectionInterval
        } else {
            injectionCountdown--
        }

        return event.copy(message = message)
    }
}
