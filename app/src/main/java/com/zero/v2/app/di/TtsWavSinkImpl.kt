package com.zero.v2.app.di

import com.zero.v2.app.data.WorkDirRepository
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.core.trace.NodeType
import com.zero.v2.services.realtime.TtsWavSink
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S4.2（D125/C4/G17）：TtsWavSink app 侧实现——WAV 经 WorkDirRepository 落盘
 * 工作目录 downloads/ 子目录（C4：openReadStream 只检索 root/downloads 两层，
 * tts/ 新子目录会读不到——复用 downloads/ 零改动）。G17 basename 强制净化在此收口
 * （WebFetchModule.sanitizeName 同款规则）：LLM 无路径自由度，剥穿越/非法字符。
 */
@Singleton
class TtsWavSinkImpl @Inject constructor(
    private val workDir: WorkDirRepository,
    private val traceManager: AgentTraceManager
) : TtsWavSink {

    companion object {
        private const val DOWNLOAD_SUBDIR = "downloads"
        /** 语音文件安全上限（远超正常语段；防失控生成） */
        private const val MAX_WAV_BYTES = 64L * 1024 * 1024
    }

    override suspend fun writeWavToDownloads(fileName: String, wav: ByteArray): String? = try {
        val safe = sanitizeTtsName(fileName)
        val result = workDir.writeBinaryStream(
            DOWNLOAD_SUBDIR, safe, "audio/wav", MAX_WAV_BYTES, ByteArrayInputStream(wav))
        if (result.error != null) {
            traceManager.record("tts", NodeType.ERROR, "speech_tts",
                "tts_wav_sink_failed: ${result.error.take(60)}")
            null
        } else result.finalName
    } catch (t: Throwable) {
        traceManager.record("tts", NodeType.ERROR, "speech_tts",
            "tts_wav_sink_error: ${t.javaClass.simpleName} ${t.message?.take(60)}")
        null
    }

    /** G17 净化（D57 同款）：仅 basename、剥非法字符、缺省自动命名、强制 .wav 扩展 */
    private fun sanitizeTtsName(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            .replace(Regex("[/\\\\:*?\"<>|%#]"), "_")
        if (name.isBlank() || name == "." || name == ".." || name.startsWith(".")) {
            name = "tts_${System.currentTimeMillis()}"
        }
        name = name.take(120)
        return if (name.endsWith(".wav", ignoreCase = true)) name else "$name.wav"
    }
}
