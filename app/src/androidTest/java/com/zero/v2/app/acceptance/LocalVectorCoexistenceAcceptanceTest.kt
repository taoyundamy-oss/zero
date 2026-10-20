package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.zero.v2.services.embedding.LocalBgeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** No microphone/network: checks native runtime coexistence using synthetic silence and text. */
class LocalVectorCoexistenceAcceptanceTest {
    @Test fun localVectorAndExistingAsrRunConcurrently() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "p7-bge")
        val scoped = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val provider = LocalBgeProvider(scoped)
        val asr = File(context.filesDir, "sherpa/asr")
        listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt").forEach {
            assertTrue("Existing ASR asset required", File(asr, it).isFile)
        }
        val baseline = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
        val transducer = OnlineTransducerModelConfig.builder()
            .setEncoder(File(asr, "encoder.onnx").path).setDecoder(File(asr, "decoder.onnx").path)
            .setJoiner(File(asr, "joiner.onnx").path).build()
        val model = OnlineModelConfig.builder().setTransducer(transducer)
            .setTokens(File(asr, "tokens.txt").path).setNumThreads(2).build()
        val recognizer = OnlineRecognizer(OnlineRecognizerConfig.builder().setOnlineModelConfig(model).build())
        val stream = recognizer.createStream()
        val latencies = mutableListOf<Long>()
        var decoded = 0
        val started = SystemClock.elapsedRealtime()
        try {
            withTimeout(60_000) {
                coroutineScope {
                    val speech = async(Dispatchers.Default) {
                        repeat(50) {
                            stream.acceptWaveform(FloatArray(3200), 16000)
                            while (recognizer.isReady(stream)) { recognizer.decode(stream); decoded++ }
                        }
                        recognizer.getResult(stream)
                    }
                    val vectors = async(Dispatchers.Default) {
                        repeat(20) {
                            val tick = SystemClock.elapsedRealtime()
                            val value = provider.embed("合成并发验收：保留原始数据，核对备份摘要，再确认任务成功。".repeat(12))
                            assertEquals(512, requireNotNull(value).size)
                            latencies += SystemClock.elapsedRealtime() - tick
                        }
                    }
                    speech.await(); vectors.await()
                }
            }
            assertTrue(decoded > 0)
            File(context.cacheDir, "p5-local-coexistence-report.json").writeText(JSONObject()
                .put("passed", true).put("embeddingLatencyMs", JSONArray(latencies)).put("asrDecodeSteps", decoded)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("pssBeforeKb", baseline).put("pssAfterKb", Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss)
                .put("audio", "10_seconds_synthetic_silence").put("microphoneUsed", false).put("cloudRequests", 0)
                .put("energyAcceptance", "not_measured").put("speechAccuracyAcceptance", "not_measured").toString(2))
        } finally { stream.release(); recognizer.release() }
    }
}
