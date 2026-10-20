package com.zero.v2.core.bus

import com.zero.v2.core.common.ChatSendEvent

/**
 * 消息过滤器接口。
 *
 * ⚠️ 性能契约（v2.1）：
 *   每个 process() 调用必须在 100ms 内完成。
 *
 * 禁止操作：
 *   - 网络请求（OkHttp / HttpURLConnection）
 *   - 数据库读写（Room / DataStore）
 *   - 文件 I/O
 *   - 大文本正则回溯（>100 字符的复杂正则）
 *
 * 允许操作：
 *   - 字符串拼接、长度检查、截断
 *   - 简单正则匹配（如时间戳格式化、前缀检测）
 *   - 计数器操作（injectionCountdown）
 *   - 时间戳获取（System.currentTimeMillis / LocalDateTime.now）
 *
 * 实现类：
 *   - MessageFilterModule: filterOrder = -100（最先执行 — 时间戳注入）
 */
interface IMessageFilter {

    /**
     * 处理顺序。数值越小越先执行。
     *
     * 典型值：
     *   -100: MessageFilterModule（时间戳注入 — 必须最先）
     *    0:   默认
     */
    val filterOrder: Int

    /**
     * 处理消息。
     *
     * @param event 输入消息事件
     * @return 处理后的消息事件
     */
    suspend fun process(event: ChatSendEvent): ChatSendEvent
}
