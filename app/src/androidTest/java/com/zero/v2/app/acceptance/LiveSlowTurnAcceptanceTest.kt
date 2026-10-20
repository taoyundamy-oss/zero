package com.zero.v2.app.acceptance

import android.content.Intent
import android.media.AudioRecord
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.background.LiveSessionService
import com.zero.v2.services.realtime.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual audio/service owners; only the response boundary is a deliberately noncooperative fixture. */
class LiveSlowTurnAcceptanceTest {
    @Test fun slowResponseCannotDelayEndOrPublishIntoNextSession() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val voice = EntryPointAccessors.fromApplication(context, LiveAcceptanceEntryPoint::class.java).voice()
        val activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val bridge = voice.javaClass.getDeclaredField("chatEngine").apply { isAccessible = true }
        val original = bridge.get(voice)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val late = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            voice.events().collect { if (it is RealtimeVoiceEvent.AssistantText && it.text.contains("late-probe")) late.incrementAndGet() }
        }
        try {
            inst.runOnMainSync { LiveSessionService.start(context) }
            val mic = voice.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }
            val deadline = android.os.SystemClock.uptimeMillis()+60000
            while ((mic.get(voice) as? AudioRecord)?.recordingState != AudioRecord.RECORDSTATE_RECORDING &&
                android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(50)
            assertEquals(AudioRecord.RECORDSTATE_RECORDING,(mic.get(voice) as AudioRecord).recordingState)
            bridge.set(voice, object : ChatEngineBridge {
                override suspend fun chatAndCollect(userText: String, source: String,
                    onStatus: ((String) -> Unit)?, onSentence: ((String) -> Unit)?,
                    triggerId: String?, systemSource: String?): String {
                    entered.countDown()
                    Thread.sleep(2200) // Models a blocking native/client operation that ignores cancellation.
                    onStatus?.invoke("late-probe-status")
                    onSentence?.invoke("late-probe-sentence")
                    finished.countDown()
                    return "late-probe-reply"
                }
                override fun isConversationIdle() = true
                override fun activeTurn(): com.zero.v2.core.common.TurnContext? = null
                override suspend fun persistTurn(triggerId: String?, systemSource: String?) = 0
            })
            voice.javaClass.getDeclaredMethod("processTurn",String::class.java).apply { isAccessible=true }.invoke(voice,"fixture")
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val started=android.os.SystemClock.uptimeMillis()
            runBlocking { voice.stop() }
            val stopMs=android.os.SystemClock.uptimeMillis()-started
            runBlocking { voice.start() }
            assertTrue(finished.await(5,TimeUnit.SECONDS)); Thread.sleep(400)
            File(context.filesDir,"a9-slow-turn.json").writeText(JSONObject().put("stopMs",stopMs)
                .put("lateEvents",late.get()).put("nextState",voice.state.value.name).toString(2))
            assertTrue("End must not wait for an uncooperative response: ${stopMs}ms",stopMs<500)
            assertEquals("Old response cannot publish into a later session",0,late.get())
            assertEquals(RealtimeState.LISTENING,voice.state.value)
        } finally {
            bridge.set(voice,original); collector.cancel(); scope.cancel()
            inst.runOnMainSync { LiveSessionService.stop(context); activity.finish() }
        }
    }
}
