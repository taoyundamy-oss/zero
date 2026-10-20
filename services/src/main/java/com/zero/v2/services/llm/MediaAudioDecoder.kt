package com.zero.v2.services.llm

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** File decoding has its own codec lifecycle; never borrows the active live microphone stream. */
internal object MediaAudioDecoder {
    private const val MAX_PCM_BYTES = 4_000_000
    private const val MAX_DURATION_US = 120_000_000L
    private const val DEADLINE_NS = 30_000_000_000L
    private const val POLL_US = 10_000L
    private const val WAV_HEADER_BYTES = 44
    private const val WAV_SIZE_OFFSET = 36
    private const val WAV_FORMAT_BYTES = 16
    private const val PCM_BITS: Short = 16

    suspend fun toWav(file: File): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("文件没有音轨")
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            require(!format.containsKey(MediaFormat.KEY_DURATION) ||
                format.getLong(MediaFormat.KEY_DURATION) <= MAX_DURATION_US) { "音频超过120秒，请使用工作站分段处理" }
            return decode(extractor, format)
        } finally { extractor.release() }
    }

    private suspend fun decode(extractor: MediaExtractor, format: MediaFormat): ByteArray {
        val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            var inputEnded = false
            var outputEnded = false
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val started = System.nanoTime()
            while (!outputEnded) {
                coroutineContext.ensureActive()
                check(System.nanoTime() - started < DEADLINE_NS) { "音频解码超时" }
                if (!inputEnded) inputEnded = feed(codec, extractor)
                outputEnded = drain(codec, pcm, info)
            }
            val output = codec.outputFormat
            require(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) {
                "解码器输出不是16位PCM，请使用工作站音频工具"
            }
            return pcmWav(pcm.toByteArray(), output.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                output.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } finally { codec.release() }
    }

    private fun feed(codec: MediaCodec, extractor: MediaExtractor): Boolean {
        val index = codec.dequeueInputBuffer(POLL_US)
        if (index < 0) return false
        val buffer = requireNotNull(codec.getInputBuffer(index)).apply { clear() }
        val size = extractor.readSampleData(buffer, 0)
        val ended = size < 0
        codec.queueInputBuffer(index, 0, size.coerceAtLeast(0), if (ended) 0 else extractor.sampleTime,
            if (ended) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        if (!ended) extractor.advance()
        return ended
    }

    private fun drain(codec: MediaCodec, pcm: ByteArrayOutputStream, info: MediaCodec.BufferInfo): Boolean {
        val index = codec.dequeueOutputBuffer(info, POLL_US)
        if (index < 0) return false
        try {
            require(pcm.size() + info.size <= MAX_PCM_BYTES) { "音频解码超限，请使用工作站分段处理" }
            val bytes = ByteArray(info.size)
            requireNotNull(codec.getOutputBuffer(index)).apply { position(info.offset); get(bytes) }
            pcm.write(bytes)
            return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        } finally { codec.releaseOutputBuffer(index, false) }
    }

    private fun pcmWav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        require(pcm.isNotEmpty() && rate > 0 && channels in 1..2) { "音频解码结果无效" }
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(pcm.size + WAV_SIZE_OFFSET).put("WAVEfmt ".toByteArray())
            .putInt(WAV_FORMAT_BYTES).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
            .putShort((channels * 2).toShort()).putShort(PCM_BITS).put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }
}
