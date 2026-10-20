package com.zero.v2.services.agent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** 模块/任务拥有的协程域；close 后不再允许子任务跨生命周期存活。 */
internal class OwnedCoroutineScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    override fun close() {
        scope.cancel()
    }
}
