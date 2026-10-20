package com.zero.v2.services.llm

import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Generate a private synthetic clip; exercise the same Android decoder used by media_analyze. */
class MediaVideoFramesDeviceTest {
    @Test fun samplesBeginningMiddleAndEndFromRealMp4() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "zero-video-audit.mp4")
        encodeFixture(file)
        val frames = MediaVideoFrames.read(file)
        assertEquals(3, frames.size)
        val colors = frames.map { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertTrue(bitmap.width <= 1024 && bitmap.height <= 1024)
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            } finally { bitmap.recycle() }
        }
        assertTrue("Beginning must be red", android.graphics.Color.red(colors[0]) > 180)
        assertTrue("Middle must be green", android.graphics.Color.green(colors[1]) > 180)
        assertTrue("End must be blue", android.graphics.Color.blue(colors[2]) > 180)
        // Retain this small generated fixture in the test app for the app-level attachment test.
    }

    private fun encodeFixture(file: File) {
        val width = 160
        val height = 160
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 120000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 4)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var sent = 0
            var inputDone = false
            var outputDone = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!outputDone) {
                check(System.nanoTime() < deadline) { "Fixture encoder timed out" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        if (sent == 12) {
                            codec.queueInputBuffer(index, 0, 0, sent * 250000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val yuv = when (sent / 4) {
                                0 -> intArrayOf(81, 90, 240)
                                1 -> intArrayOf(145, 54, 34)
                                else -> intArrayOf(41, 240, 110)
                            }
                            val image = checkNotNull(codec.getInputImage(index))
                            image.planes.forEachIndexed { p, plane ->
                                val edge = if (p == 0) width else width / 2
                                for (y in 0 until edge) for (x in 0 until edge) {
                                    plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, yuv[p].toByte())
                                }
                            }
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, sent * 250000L, 0)
                            sent++
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(started)
                        muxer.writeSampleData(track, checkNotNull(codec.getOutputBuffer(index)), info)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }
}
