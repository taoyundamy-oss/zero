package com.zero.v2.app.acceptance

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioRecord
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.background.LiveSessionService
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

/** Real FGS and media owners. Permission is prepared/restored externally; no personal configuration writes. */
class LiveMediaAcceptanceTest {
    @Test fun fiveMinuteRecordingInterruptAndBackgroundSoak() {
        val activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val samples=org.json.JSONArray()
        val voice=entry().voice()
        val bridge=voice.javaClass.getDeclaredField("chatEngine").apply { isAccessible=true }
        val original=bridge.get(voice)
        try {
            // Bound responses without writing personal conversations or calling a paid model.
            bridge.set(voice,object : com.zero.v2.services.realtime.ChatEngineBridge {
                override suspend fun chatAndCollect(userText:String,source:String,onStatus:((String)->Unit)?,
                    onSentence:((String)->Unit)?,triggerId:String?,systemSource:String?)="隔离验收响应"
                override fun isConversationIdle()=true
                override fun activeTurn():com.zero.v2.core.common.TurnContext?=null
                override suspend fun persistTurn(triggerId:String?,systemSource:String?)=0
            })
            inst.runOnMainSync { LiveSessionService.start(context) }
            await("Actual recording before soak",60000) { microphone()?.recordingState==AudioRecord.RECORDSTATE_RECORDING }
            val started=android.os.SystemClock.uptimeMillis()
            repeat(30) { index ->
                val until=started+(index+1)*10000L
                while(android.os.SystemClock.uptimeMillis()<until) {
                    assertTrue("Service must survive soak",running())
                    assertTrue(voice.isActive())
                    assertEquals(AudioRecord.RECORDSTATE_RECORDING,microphone()?.recordingState)
                    Thread.sleep(250)
                }
                inst.runOnMainSync { voice.interrupt() }
                Thread.sleep(250)
                assertEquals(com.zero.v2.services.realtime.RealtimeState.LISTENING,voice.state.value)
                if(index==9) inst.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
                if(index==19) inst.runOnMainSync { context.startActivity(Intent(context,VisualAcceptanceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                if(index%3==0) {
                    val info=android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(info)
                    samples.put(JSONObject().put("elapsedMs",android.os.SystemClock.uptimeMillis()-started)
                        .put("debugPssKiB",info.totalPss).put("audioSessionId",microphone()!!.audioSessionId))
                }
            }
            inst.runOnMainSync { LiveSessionService.stop(context) }
            await("Soak end releases recording") { !running() && !voice.isActive() && microphone()==null }
            Thread.sleep(1000); assertNull(microphone())
            File(context.filesDir,"a9-soak.json").writeText(JSONObject().put("durationMs",android.os.SystemClock.uptimeMillis()-started)
                .put("interruptions",30).put("backgroundReturn",true).put("samples",samples)
                .put("endReleased",true).put("responseBoundary","offline fixture; real audio and service").toString(2))
        } finally { bridge.set(voice,original); LiveSessionService.stop(context); inst.runOnMainSync { activity.finish() } }
    }
    @Test fun earlyServiceEndCannotStartLateAndFreshServiceCanRecord() {
        val activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            repeat(5) {
                inst.runOnMainSync { LiveSessionService.start(context) }
                await("Service created before early end") { running() }
                inst.runOnMainSync { LiveSessionService.stop(context) }
                await("Early end stops service") { !running() && !entry().voice().isActive() && microphone() == null }
                Thread.sleep(200)
                assertNull("Cancelled initialization cannot record late", microphone())
            }
            inst.runOnMainSync { LiveSessionService.start(context) }
            await("Fresh service records after cancelled startup", 60000) {
                microphone()?.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }
            inst.runOnMainSync { LiveSessionService.stop(context) }
            await("Final end releases all media") { !running() && !entry().voice().isActive() && microphone() == null }
            Thread.sleep(700); assertNull(microphone())
            File(context.filesDir,"a9-early-end.json").writeText(JSONObject()
                .put("earlyEnds",5).put("freshStartRecorded",true).put("lateRecordingAbsent",true).toString(2))
        } finally { LiveSessionService.stop(context); inst.runOnMainSync { activity.finish() } }
    }
    @Test fun rapidRestartHasOneRecordingLoopAndNoLateReopen() {
        val activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            inst.runOnMainSync { LiveSessionService.start(context) }
            await("Initial real recording", 60000) { microphone()?.recordingState == AudioRecord.RECORDSTATE_RECORDING }
            val voice = entry().voice()
            val field = voice.javaClass.getDeclaredField("scope").apply { isAccessible = true }
            val scope = field.get(voice) as kotlinx.coroutines.CoroutineScope
            val root = requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job])
            repeat(8) {
                kotlinx.coroutines.runBlocking { voice.stop(); voice.start() }
                await("Restart $it must record") { microphone()?.recordingState == AudioRecord.RECORDSTATE_RECORDING }
                Thread.sleep(150)
            }
            val jobs = root.children.count { it.isActive }
            File(context.filesDir, "a9-start-race.json").writeText(JSONObject()
                .put("rapidRestarts",8).put("activeProviderJobs",jobs).toString(2))
            assertEquals("Quiet session must have exactly one recording loop after rapid restarts", 1, jobs)
            inst.runOnMainSync { LiveSessionService.stop(context) }
            await("End releases service and recording") { !running() && !voice.isActive() && microphone() == null }
            Thread.sleep(700)
            assertNull("Old loops cannot reopen microphone after end", microphone())
            assertEquals(0, root.children.count { it.isActive })
        } finally { LiveSessionService.stop(context); inst.runOnMainSync { activity.finish() } }
    }
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext
    private fun entry() = EntryPointAccessors.fromApplication(context, LiveAcceptanceEntryPoint::class.java)
    @Suppress("DEPRECATION")
    private fun running() = (context.getSystemService(ActivityManager::class.java)).getRunningServices(100)
        .any { it.service.className == LiveSessionService::class.java.name }
    private fun await(label: String, limit: Long = 15000, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + limit
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        assertTrue(label, condition())
    }
    private fun microphone(): AudioRecord? {
        val field = entry().voice().javaClass.getDeclaredField("audioRecord")
        field.isAccessible = true
        return field.get(entry().voice()) as? AudioRecord
    }
    private fun captureField(name: String): Any? {
        val field = entry().capture().javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(entry().capture())
    }
    private fun consent(grant: Boolean) {
        inst.runOnMainSync { context.startActivity(Intent(context,
            com.zero.v2.app.background.ProjectionConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val labels = if (grant) setOf("立即开始", "开始", "開始", "启动", "Start now", "Start")
            else setOf("取消", "Cancel")
        var found: android.view.accessibility.AccessibilityNodeInfo? = null
        await("Real system projection consent must be visible") {
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser && node.text?.toString() in labels &&
                    node.packageName?.toString() != context.packageName) found = node
                for (i in 0 until node.childCount) find(node.getChild(i))
            }
            find(inst.uiAutomation.rootInActiveWindow)
            found != null
        }
        var node = found!!
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        if (grant) {
            var selection: android.view.accessibility.AccessibilityNodeInfo? = null
            await("System app-sharing selector must resolve to Zero or active projection") {
                fun scan(candidate: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (candidate == null) return
                    if (candidate.isVisibleToUser && candidate.text?.toString() == "Zero" &&
                        candidate.packageName?.toString() == "com.android.systemui") selection = candidate
                    for (i in 0 until candidate.childCount) scan(candidate.getChild(i))
                }
                scan(inst.uiAutomation.rootInActiveWindow)
                selection != null || entry().capture().isSharingActive()
            }
            selection?.let {
                var owner = it
                while (!owner.isClickable) owner = requireNotNull(owner.parent)
                assertTrue(owner.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            }
        }
    }
    @Test fun deniedPermissionCannotLeaveLiveServiceOrMediaActive() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            inst.runOnMainSync { LiveSessionService.start(context) }
            Thread.sleep(12000) // Also cross Android's pending foreground-start timeout.
            await("Denied microphone must not leave a foreground Live service", 3000) { !running() }
            assertFalse(entry().voice().isActive()); assertNull(microphone()); assertFalse(entry().capture().isSharingActive())
            File(context.filesDir,"a9-media-denied.json").writeText(JSONObject().put("denied",true)
                .put("serviceStopped",true).put("microphoneReleased",true).toString(2))
        } finally { LiveSessionService.stop(context); inst.runOnMainSync { activity.finish() } }
    }
    @Test fun realMicrophoneBackgroundReturnAndEndRelease() {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        var record: AudioRecord? = null
        try {
            inst.runOnMainSync { LiveSessionService.start(context) }
            await("Real microphone must record", 60000) {
                entry().voice().isActive() && microphone()?.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }
            record = microphone()
            consent(false)
            Thread.sleep(250)
            assertFalse("Denied real consent cannot create projection", entry().capture().isSharingActive())
            assertTrue("Consent cancellation preserves microphone session", entry().voice().isActive())
            consent(true)
            await("Actual system consent must create projection") { entry().capture().sharing.value }
            assertNotNull(captureField("virtualDisplay")); assertNotNull(captureField("imageReader"))
            val projection = captureField("projection") as android.media.projection.MediaProjection
            projection.stop()
            await("Actual system stop callback must clear projection resources") {
                !entry().capture().isSharingActive() && captureField("virtualDisplay") == null && captureField("imageReader") == null
            }
            assertTrue("Projection stop preserves microphone session", entry().voice().isActive())
            consent(true)
            await("Fresh consent can create second projection") { entry().capture().sharing.value }
            inst.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
            Thread.sleep(300)
            assertTrue("Returning from page must preserve session", running())
            assertTrue(entry().voice().isActive())
            inst.runOnMainSync { LiveSessionService.stop(context) }
            await("End must release real AudioRecord and service") { !running() && !entry().voice().isActive() && microphone() == null }
            assertEquals("Old AudioRecord must be released", AudioRecord.STATE_UNINITIALIZED, record!!.state)
            assertFalse(entry().capture().isSharingActive())
            assertNull(captureField("virtualDisplay")); assertNull(captureField("imageReader"))
            Thread.sleep(500); assertNull("No late microphone reopen after end", microphone())
            File(context.filesDir,"a9-media-release.json").writeText(JSONObject().put("realMicrophoneRecorded",true)
                .put("backgroundSessionPreserved",true).put("oldAudioRecordReleased",true)
                .put("realConsentCancel",true).put("realConsentGrant",true).put("systemProjectionStopClearedResources",true)
                .put("secondProjectionReleasedOnEnd",true)
                .put("lateReopenAbsent",true).toString(2))
        } finally { LiveSessionService.stop(context); inst.runOnMainSync { activity.finish() } }
    }
    /** External orchestrator revokes actual permission after this marker; OS process death is expected. */
    @Test fun realRecordingRevocationWindow() {
        val activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            inst.runOnMainSync { LiveSessionService.start(context) }
            await("Real microphone recording before revocation", 60000) {
                microphone()?.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }
            File(context.filesDir,"a9-media-revocation-ready.json").writeText(JSONObject()
                .put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid())
                .put("audioSessionId",microphone()!!.audioSessionId).put("recording",true).toString(2))
            Thread.sleep(60000)
            fail("External revocation did not terminate recording process within the observation window")
        } finally { LiveSessionService.stop(context); inst.runOnMainSync { activity.finish() } }
    }
}
