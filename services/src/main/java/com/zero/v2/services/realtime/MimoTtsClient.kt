package com.zero.v2.services.realtime

import android.os.SystemClock
import android.util.Base64
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * MiMo v2.5 TTS 流式客户端 — v5.7 在线语音合成。
 *
 * 小米 MiMo 开放平台（mimo.mi.com），OpenAI 兼容接口：
 *   POST https://api.xiaomimimo.com/v1/chat/completions
 *   model=mimo-v2.5-tts + audio={format:"pcm16", voice} + stream=true
 * SSE 逐块返回 choices[0].delta.audio.data（base64 pcm16 / 24kHz / 单声道 / LE）。
 *
 * 计费：限时免费。合成文本放 assistant 角色消息（user 角色为可选风格指令，预留语气控制）。
 */
@Singleton
class MimoTtsClient @Inject constructor(
    @Named("llm") private val client: OkHttpClient,
    private val traceManager: AgentTraceManager
) {
    companion object {
        const val ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
        const val MODEL = "mimo-v2.5-tts"
        /** pcm16 固定 24kHz 单声道 */
        const val SAMPLE_RATE = 24000
    }

    data class MimoStats(val firstChunkMs: Long, val chunks: Int, val totalMs: Long)

    /**
     * 流式合成：每收到一块 24kHz pcm16 字节即回调 [onPcm]（挂起回调 = 天然背压，
     * v6.16/D54：回调内可挂起——播报方在回调中插入静默探针窗）。
     * @param shouldStop 轮询停止（打断/挂断）→ 提前中断读取
     * @throws IllegalStateException Key 未配置 / HTTP 错误
     */
    suspend fun streamPcm(
        text: String,
        voice: String,
        apiKey: String,
        shouldStop: () -> Boolean,
        onPcm: suspend (ByteArray) -> Unit
    ): MimoStats = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("stream", true)
            put("messages", JSONArray().put(
                JSONObject().put("role", "assistant").put("content", text)))
            put("audio", JSONObject().put("format", "pcm16").put("voice", voice))
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body).build()

        val t0 = SystemClock.elapsedRealtime()
        var firstChunkMs = -1L
        var chunks = 0

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = runCatching { resp.body?.string()?.take(120) }.getOrNull() ?: ""
                    throw IllegalStateException("MiMo HTTP ${resp.code}: $err")
                }
                val source = resp.body ?: throw IllegalStateException("MiMo 空响应体")
                // SSE 行协议：`data: {json}` … `data: [DONE]`
                while (true) {
                    if (shouldStop()) break
                    val line = source.source().readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val b64 = extractAudioB64(payload) ?: continue
                    if (firstChunkMs < 0) firstChunkMs = SystemClock.elapsedRealtime() - t0
                    chunks++
                    onPcm(Base64.decode(b64, Base64.DEFAULT))
                }
            }
        } catch (e: IOException) {
            // 用户主动打断导致的连接中断不是错误；其余照抛
            if (!shouldStop()) {
                traceManager.record("mimo_tts", NodeType.ERROR, "speech_tts",
                    "network_error: ${e.message?.take(60)}")
                throw e
            }
        }
        MimoStats(firstChunkMs, chunks, SystemClock.elapsedRealtime() - t0)
    }

    private fun extractAudioB64(payload: String): String? = try {
        JSONObject(payload)
            .optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("delta")?.optJSONObject("audio")
            ?.optString("data")?.takeIf { it.isNotEmpty() && it != "null" }
    } catch (_: Exception) { null }
}
