package com.zero.v2.core.bus

import com.zero.v2.core.common.ChatSendEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消息预处理管道（责任链模式）。
 *
 * 替代原设计中通过 MutableSharedFlow 修改消息的方案（有并发竞态风险）。
 * 每个 Filter 按 filterOrder 排序依次处理消息。
 * 处理完成后通过 ChatBus 广播 onChatSent（通知性质，不修改）。
 *
 * 对标 Alife MessageFilter.OnChatSend 的职责。
 *
 * v2.1 安全保护：
 *   - 全局超时 3 秒，超时则跳过所有 filter 返回原始消息
 *   - 每个 IMessageFilter 强制 100ms 超时；超时或异常只跳过当前 filter
 *
 * v2.2 优化：
 *   - 责任链按 filterOrder 确定性排序
 *   - 不允许在 process() 中执行网络/IO/DB 操作
 */
@Singleton
class MessagePipeline @Inject constructor(
    private val traceRecorder: MessagePipelineTraceRecorder
) {

    private companion object {
        const val PIPELINE_TIMEOUT_MS = 3_000L
        const val FILTER_TIMEOUT_MS = 100L
        const val PIPELINE_FILTER = "<pipeline>"
    }

    /** 已注册的消息过滤器列表（按 filterOrder 排序） */
    private val filters = mutableListOf<IMessageFilter>()

    /**
     * 注册消息过滤器。
     * 由各模块在 onStart() 中调用。
     */
    fun registerFilter(filter: IMessageFilter) {
        synchronized(filters) {
            filters.add(filter)
            filters.sortBy { it.filterOrder }
        }
    }

    /**
     * 注销消息过滤器。
     * 由各模块在 onDestroy() 中调用。
     */
    fun unregisterFilter(filter: IMessageFilter) {
        synchronized(filters) {
            filters.remove(filter)
        }
    }

    /**
     * 依次执行所有过滤器。
     *
     * @param event 原始消息事件
     * @return 处理后的消息事件。超时则返回原始 event。
     */
    suspend fun process(event: ChatSendEvent): ChatSendEvent {
        val traceId = traceRecorder.startTrace()
        return withTimeoutOrNull(PIPELINE_TIMEOUT_MS) {
            var current = event
            for (filter in filters.synchronizedCopy()) {
                val startedAt = System.nanoTime()
                try {
                    val result = withTimeout(FILTER_TIMEOUT_MS) {
                        filter.process(current)
                    }
                    current = result
                    traceRecorder.record(
                        traceId = traceId,
                        filter = filter::class.java.name,
                        outcome = FilterOutcome.COMPLETED,
                        durationMs = elapsedMillis(startedAt)
                    )
                } catch (error: TimeoutCancellationException) {
                    traceRecorder.record(
                        traceId = traceId,
                        filter = filter::class.java.name,
                        outcome = FilterOutcome.TIMEOUT,
                        durationMs = elapsedMillis(startedAt)
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    traceRecorder.record(
                        traceId = traceId,
                        filter = filter::class.java.name,
                        outcome = FilterOutcome.ERROR,
                        durationMs = elapsedMillis(startedAt),
                        errorType = error::class.java.name
                    )
                }
            }
            current
        } ?: event.also {
            traceRecorder.record(
                traceId = traceId,
                filter = PIPELINE_FILTER,
                outcome = FilterOutcome.PIPELINE_TIMEOUT,
                durationMs = PIPELINE_TIMEOUT_MS
            )
        }
    }

    private fun <T> MutableList<T>.synchronizedCopy(): List<T> {
        return synchronized(this) { this.toList() }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

}

/** 单个过滤器的执行结果，用于诊断慢过滤器和异常过滤器。 */
enum class FilterOutcome {
    COMPLETED,
    TIMEOUT,
    ERROR,
    PIPELINE_TIMEOUT
}

data class MessagePipelineTraceEvent(
    val traceId: String,
    val filter: String,
    val outcome: FilterOutcome,
    val durationMs: Long,
    val errorType: String? = null
)

/**
 * MessagePipeline 的轻量 trace sink。
 *
 * 先在 bus 层保留最近事件，避免把 Room/Trace 持久化耦合进消息过滤路径；
 * 上层可读取 snapshot 后转发到 AgentTrace。记录失败不能阻断消息处理。
 */
@Singleton
class MessagePipelineTraceRecorder @Inject constructor() {
    private val events = ArrayDeque<MessagePipelineTraceEvent>()
    private val lock = Any()
    private val maxEvents = 256

    fun startTrace(): String = UUID.randomUUID().toString().take(8)

    fun record(
        traceId: String,
        filter: String,
        outcome: FilterOutcome,
        durationMs: Long,
        errorType: String? = null
    ) {
        synchronized(lock) {
            if (events.size >= maxEvents) events.removeFirst()
            events.addLast(MessagePipelineTraceEvent(traceId, filter, outcome, durationMs, errorType))
        }
    }

    fun snapshot(): List<MessagePipelineTraceEvent> = synchronized(lock) { events.toList() }
}
