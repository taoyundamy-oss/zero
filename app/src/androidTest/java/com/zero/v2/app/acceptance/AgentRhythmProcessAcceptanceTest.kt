package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.bus.ChatBusImpl
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.common.*
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.agent.AgentRhythmPersistence
import com.zero.v2.services.agent.AgentRuntime
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Two distinct instrumentation processes, real preferences and runtime; model entry intercepted. */
class AgentRhythmProcessAcceptanceTest {
    @Test fun seedOrRestoreRealProcessOwner() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("rhythmPhase")
        assumeTrue(phase in setOf("seed", "restore"))
        assertTrue(inst is ProviderAcceptanceRunner)
        val context = inst.targetContext
        val privateContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("a10_rhythm_acceptance_$name", mode)
        }
        val marker = privateContext.getSharedPreferences("marker", Context.MODE_PRIVATE)
        val disk = privateContext.getSharedPreferences("zero_agent_rhythm", Context.MODE_PRIVATE)
        if (phase == "seed") { check(marker.edit().clear().commit()); check(disk.edit().clear().commit()) }
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), worker)
        store.saveConfirmed("agent_behavior", "{\"mode\":\"SMART\",\"selfDriveOnTick\":true}")
        val chat = ChatBusImpl()
        val system = SystemBusImpl()
        val ticks = AtomicInteger()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { system.onTick.collect { ticks.incrementAndGet() } }
        val runtime = AgentRuntime(system, chat, AgentTraceManager(db.agentTraceEventDao()), store, dagger.Lazy {
            marker.edit().putInt("attempts", marker.getInt("attempts", 0) + 1).commit()
            error("Intercepted before model, cloud, or tools")
        }, AgentRhythmPersistence(privateContext))
        runtime.config.randomOffsetSec = 0
        val report = JSONObject().put("phase", phase).put("accepted", false).put("pid", android.os.Process.myPid())
        try {
            runtime.startLoop()
            withTimeout(3000) { while (chat.onChatSent.subscriptionCount.value != 1 || ticks.get() == 0) delay(10) }
            if (phase == "seed") {
                withTimeout(3000) { while (marker.getInt("attempts", 0) != 1) delay(10) }
                repeat(4) {
                    chat.onChatSent.emit(ChatSentEvent(ChatMessage(role = MessageRole.USER,
                        content = "isolated user activity", source = MessageSource.CHAT_WINDOW), System.currentTimeMillis()))
                    delay(30)
                }
                withTimeout(3000) { while (runtime.calculateNextInterval() != 7_290_000L) delay(10) }
                check(marker.edit().putInt("seedPid", android.os.Process.myPid()).commit())
                delay(400)
            } else {
                assertNotEquals(marker.getInt("seedPid", -1), android.os.Process.myPid())
                delay(800)
                assertEquals("Restart must not repeat the initial model admission", 1, marker.getInt("attempts", 0))
                assertEquals("User deferral tier must survive process death", 7_290_000L, runtime.calculateNextInterval())
                runtime.kick()
                delay(500)
                assertEquals(1, marker.getInt("attempts", 0))
                report.put("differentProcess", true).put("kickDidNotBypassDeadline", true)
            }
            report.put("accepted", true).put("modelAttempts", marker.getInt("attempts", 0))
                .put("heartbeatObserved", ticks.get() > 0).put("intervalMs", runtime.calculateNextInterval())
        } finally {
            runtime.onDestroy(); observer.cancelAndJoin(); worker.cancel(); db.close()
            File(context.filesDir, "a10-rhythm-$phase.json").writeText(report.toString(2))
            if (phase == "restore") { marker.edit().clear().commit(); disk.edit().clear().commit() }
        }
    }
}
