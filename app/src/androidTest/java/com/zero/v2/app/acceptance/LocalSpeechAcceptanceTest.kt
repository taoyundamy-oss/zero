package com.zero.v2.app.acceptance

import android.Manifest
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.services.embedding.LocalBgeProvider
import com.zero.v2.services.realtime.SpeechRecognitionModule
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real microphone -> production local ASR alongside local vectors. No saved audio or cloud request. */
class LocalSpeechAcceptanceTest {
    @Test fun spokenSentenceWithConcurrentLocalVectors(): Unit = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        assertEquals("Existing microphone permission is required; test never changes it",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val root = File(context.cacheDir, "p5-resource").apply { mkdirs() }
        val report = JSONObject().put("passed", false).put("microphoneUsed", false).put("cloudCalls", 0)
            .put("audioRetained", false)
            .put("scope", "production local ASR with concurrent embeddings; not Live agent/TTS acceptance")
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val asr = SpeechRecognitionModule(context, AgentTraceManager(db.agentTraceEventDao()), entry.llmClient(), entry.profiles())
        val scoped = object : ContextWrapper(context) { override fun getFilesDir() = File(context.cacheDir, "p7-bge") }
        val provider = LocalBgeProvider(scoped)
        val activity = inst.startActivitySync(Intent(context, WindowAcceptanceAnchorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun show(text: String) = inst.runOnMainSync {
            activity.setContentView(TextView(activity).apply { this.text = text; textSize = 24f })
        }
        var recorder: AudioRecord? = null
        try {
            entry.lifecycleManager().safeCall(asr, "acceptance_asr_init") { asr.onAwake() }
            assertTrue(asr.ready.value)
            assertTrue(provider.isAvailable())
            val gate = File(root, "start-voice"); gate.delete()
            File(root, "voice-ready").writeText("ready")
            show("本地语音验收准备好。\n请等待开始提示，随后朗读：\n$EXPECTED")
            inst.runOnMainSync {
                activity.setContentView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply { text = "点击开始后倒计时3秒，再朗读：\n$EXPECTED"; textSize = 24f })
                    addView(Button(activity).apply {
                        text = "开始朗读（本地录音12秒）"
                        setOnClickListener { gate.writeText("user-start"); isEnabled = false }
                    })
                })
            }
            val waitUntil = SystemClock.elapsedRealtime() + 300_000
            while (!gate.exists()) { check(SystemClock.elapsedRealtime() < waitUntil) { "voice_wait_timeout" }; delay(250) }
            repeat(3) { show("${3-it} 秒后开始朗读：\n$EXPECTED"); delay(1000) }
            val buffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(buffer > 0)
            val mic = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(buffer * 4, 16000)).also { recorder = it }
            assertEquals(AudioRecord.STATE_INITIALIZED, mic.state)
            mic.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, mic.recordingState)
            report.put("microphoneUsed", true)
            show("正在本地识别（12秒），请朗读一次：\n$EXPECTED")
            val latencies = JSONArray(); val completed = mutableListOf<String>(); var partial = ""
            var sampleCount = 0; var squared = 0.0
            suspend fun accept(samples: FloatArray) {
                entry.lifecycleManager().safeCall(asr, "acceptance_asr_pcm") { asr.processAudioChunk(samples) }?.let {
                    partial = it.first
                    if (it.second && partial.isNotBlank()) { completed += partial; partial = "" }
                }
            }
            coroutineScope {
                val vectors = async(Dispatchers.Default) {
                    repeat(20) {
                        val tick = SystemClock.elapsedRealtime()
                        assertEquals(512, requireNotNull(provider.embed("合成语音并发验收，保留旧文件并检查备份。".repeat(12))).size)
                        latencies.put(SystemClock.elapsedRealtime() - tick); delay(400)
                    }
                }
                val until = SystemClock.elapsedRealtime() + 12_000; val pcm = ShortArray(320)
                while (SystemClock.elapsedRealtime() < until) {
                    val count = mic.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "microphone_read_failed" }
                    if (count == 0) { delay(10); continue }
                    val samples = FloatArray(count) { pcm[it] / 32768f }
                    sampleCount += count; squared += samples.sumOf { (it * it).toDouble() }; accept(samples)
                }
                mic.stop()
                repeat(120) { accept(FloatArray(320)) }
                vectors.await()
            }
            if (partial.isNotBlank()) completed += partial
            val actual = normalize(completed.joinToString("")); val expected = normalize(EXPECTED)
            val cer = distance(expected, actual).toDouble() / expected.length
            report.put("samples", sampleCount).put("rms", kotlin.math.sqrt(squared / sampleCount.coerceAtLeast(1)))
                .put("cer", cer).put("recognizedCharacters", actual.length)
                .put("recognizedSha256", MessageDigest.getInstance("SHA-256").digest(actual.toByteArray())
                    .joinToString("") { "%02x".format(it) })
                .put("negationPreserved", actual.contains("不要删除"))
                .put("embeddingLatencyMs", latencies)
            assertTrue("Expected speech was not captured accurately; see private-free metrics", cer <= 0.25)
            assertTrue("Negation must survive recognition", actual.contains("不要删除"))
            report.put("passed", true)
        } finally {
            recorder?.release()
            entry.lifecycleManager().safeCall(asr, "acceptance_asr_release") { asr.onDestroy() }
            File(root, "speech.json").writeText(report.toString(2))
            inst.runOnMainSync { activity.finish() }; db.close()
        }
    }
    private fun normalize(text: String) = text.replace("三", "3").filter { it.isLetterOrDigit() }.lowercase()
    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, left ->
            val next = IntArray(b.length + 1); next[0] = i + 1
            b.forEachIndexed { j, right -> next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1,
                previous[j] + if (left == right) 0 else 1) }; previous = next
        }
        return previous.last()
    }
    private companion object { const val EXPECTED = "今天下午三点提醒我检查备份，不要删除旧文件。" }
}
