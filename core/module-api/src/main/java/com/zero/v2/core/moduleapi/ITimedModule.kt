package com.zero.v2.core.moduleapi

/**
 * 心跳驱动模块接口。
 *
 * 对标 Alife 的 ITimeIterative.OnUpdate(ref float seconds)。
 * 实现此接口的模块会在每个心跳帧收到 onTick 回调。
 *
 * 适用模块：
 *   - SystemEventModule: 检查提醒、动态报点、自动化调度
 *   - UsageStatsModule: 定期收集使用量数据
 *   - BackgroundServiceModule: 心跳循环驱动力
 *
 * 性能契约：
 *   onTick() 必须在 100ms 内完成（轻量检查）。
 *   禁止在 onTick() 中执行网络请求、LLM 调用、DB 写入。
 *   重量操作应通过 SystemBus 事件分发到 WorkManager 执行。
 */
interface ITimedModule : IZeroModule {

    /**
     * 每个心跳帧调用。
     *
     * @param tickMs 当前时间戳 (epoch millis)
     */
    suspend fun onTick(tickMs: Long)
}
