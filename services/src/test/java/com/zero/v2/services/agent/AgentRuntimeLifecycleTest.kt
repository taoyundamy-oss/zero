package com.zero.v2.services.agent

import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.MessageSource
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.store.ModuleConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class AgentRuntimeLifecycleTest {
    @Test fun `new runtime owner must not spend another tick before the previous deadline`() = runBlocking {
        val attempts = AtomicInteger()
        val store = ModuleConfigStore(noOpDao(true))
        var saved: String? = null
        val rhythm = AgentRhythmPersistence({ saved }, { saved = it; true })
        fun owner() = AgentRuntime(SystemBusImpl(), ChatBusImpl(), AgentTraceManager(noOpDao()), store,
            dagger.Lazy { attempts.incrementAndGet(); error("Intercepted before network") }, rhythm)
        val first = owner()
        var second: AgentRuntime? = null
        try {
            first.startLoop()
            withTimeout(2000) { while (attempts.get() == 0) delay(5) }
            delay(400)
            first.onDestroy()
            second = owner()
            second.startLoop()
            delay(450)
            assertEquals("A new process owner must restore the outstanding self-drive deadline", 1, attempts.get())
        } finally { first.onDestroy(); second?.onDestroy() }
    }

    @Test fun `formatted config honors self drive enabled and resume disabled`() = runBlocking {
        val attempts = AtomicInteger()
        val chat = ChatBusImpl()
        val runtime = AgentRuntime(SystemBusImpl(), chat, AgentTraceManager(noOpDao()),
            ModuleConfigStore(noOpDao(true, formatted = true)), dagger.Lazy {
                attempts.incrementAndGet(); error("No network")
            })
        try {
            runtime.processAutonomousTick(1_000L)
            assertEquals("JSON whitespace must not disable the configured tick", 1, attempts.get())
            assertEquals("OFF must gate task resume regardless of JSON whitespace",
                false, runtime.isTaskResumeAllowed())
            runtime.startLoop()
            delay(100)
            assertEquals("OFF must also block loop startup", 0, chat.onChatSent.subscriptionCount.value)
        } finally {
            runtime.onDestroy()
        }
    }

    @Test fun `continued user presence stays quiet past six hours then absence resets the wave`() = runBlocking {
        val attempts = AtomicInteger()
        val runtime = AgentRuntime(SystemBusImpl(), ChatBusImpl(), AgentTraceManager(noOpDao()),
            ModuleConfigStore(noOpDao(true)), dagger.Lazy { attempts.incrementAndGet(); error("No network") })
        runtime.config.randomOffsetSec = 0
        try {
            repeat(4) { runtime.noteUserMessage(MessageSource.CHAT_WINDOW, 1_000L + it) }
            runtime.processAutonomousTick(2_000L)
            assertEquals(7_290_000L, runtime.calculateNextInterval())
            val sevenHours = 7 * 3_600_000L
            runtime.noteUserMessage(MessageSource.VOICE, sevenHours)
            runtime.processAutonomousTick(sevenHours + 1)
            assertEquals(7_290_000L, runtime.calculateNextInterval())
            assertEquals(0, attempts.get())
            runtime.processAutonomousTick(sevenHours + 2)
            assertEquals(90_000L, runtime.calculateNextInterval())
            assertEquals("Reset tick itself remains silent", 0, attempts.get())
            runtime.processAutonomousTick(sevenHours + 90_002)
            assertEquals(1, attempts.get())
        } finally {
            runtime.onDestroy()
        }
    }

    @Test fun `scheduled kick emits heartbeat without an extra autonomous request`() = runBlocking {
        val attempts = AtomicInteger()
        val bus = SystemBusImpl()
        val ticks = AtomicInteger()
        val observer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            bus.onTick.collect { ticks.incrementAndGet() }
        }
        val runtime = AgentRuntime(bus, ChatBusImpl(), AgentTraceManager(noOpDao()),
            ModuleConfigStore(noOpDao(true)), dagger.Lazy {
                attempts.incrementAndGet()
                error("Intercepted before network")
            })
        try {
            runtime.startLoop()
            withTimeout(2_000) { while (attempts.get() == 0) delay(5) }
            delay(400)
            val before = ticks.get()
            runtime.kick()
            withTimeout(2_000) { while (ticks.get() <= before) delay(5) }
            delay(100)
            assertEquals("A scheduled heartbeat must not bypass the autonomous interval", 1, attempts.get())
        } finally {
            runtime.onDestroy()
            observer.cancel()
        }
    }

    @Test fun `stopping runtime removes chat subscription before restarting`() = runBlocking {
        val chat = ChatBusImpl()
        val runtime = AgentRuntime(SystemBusImpl(), chat,
            AgentTraceManager(noOpDao()), ModuleConfigStore(noOpDao()),
            dagger.Lazy { error("No paid trigger expected") })
        try {
            runtime.startLoop()
            withTimeout(2_000) { while (chat.onChatSent.subscriptionCount.value == 0) delay(5) }
            runtime.stopLoop()
            delay(100)
            assertEquals("Stopped runtime must release its subscriber", 0, chat.onChatSent.subscriptionCount.value)
            runtime.startLoop()
            withTimeout(2_000) { while (chat.onChatSent.subscriptionCount.value == 0) delay(5) }
            assertEquals(1, chat.onChatSent.subscriptionCount.value)
        } finally {
            runtime.onDestroy()
        }
    }

    private inline fun <reified T> noOpDao(
        selfDrive: Boolean = false,
        formatted: Boolean = false,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getByModuleId" -> if (selfDrive) {
                val json = if (formatted) "{\"selfDriveOnTick\": true, \"mode\": \"OFF\"}"
                    else "{\"selfDriveOnTick\":true}"
                ModuleConfigEntity("agent_behavior", json)
            } else null
            else -> Unit
        }
    } as T
}
