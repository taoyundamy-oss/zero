package com.zero.v2.services.llm

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File

/** Bounded frame sampling shared by automatic and explicitly requested video analysis. */
internal object MediaVideoFrames {
    private const val FRAME_EDGE = 1024
    private const val JPEG_QUALITY = 85
    private const val MICROSECONDS_PER_MILLISECOND = 1000L
    private const val LEGACY_MAX_PIXELS = 4_194_304L
    suspend fun read(file: File): List<ByteArray> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("无法读取视频时长")
            require(duration > 0) { "视频为空" }
            return listOf(0L, duration / 2, (duration - 1).coerceAtLeast(0)).distinct().map { milliseconds ->
                kotlin.coroutines.coroutineContext.ensureActive()
                val frame = readFrame(retriever, milliseconds * MICROSECONDS_PER_MILLISECOND)
                try {
                    val bytes = ByteArrayOutputStream().use { output ->
                        check(frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
                        output.toByteArray()
                    }
                    bytes
                } finally { frame.recycle() }
            }
        } finally { retriever.release() }
    }

    private fun readFrame(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap {
        val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, FRAME_EDGE, FRAME_EDGE)
        } else {
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toLongOrNull() ?: 0
            require(width * height in 1..LEGACY_MAX_PIXELS) { "此设备的视频分辨率超出内存保护范围，请使用工具处理" }
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }
        return frame ?: error("视频抽帧失败")
    }
}
