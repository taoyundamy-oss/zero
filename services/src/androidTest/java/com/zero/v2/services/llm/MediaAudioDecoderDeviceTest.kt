package com.zero.v2.services.llm

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises Android's actual extractor/codec rather than mocking PCM decoding. No provider requests. */
class MediaAudioDecoderDeviceTest {
    @Test fun wavInputProducesPlayablePcmWithoutUsingLiveMicrophone() = runBlocking {
        val pcm = ByteArray(3200)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(pcm.size + 36).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        val file = File.createTempFile("media-audit-", ".wav", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(header.array() + pcm)
            val output = MediaAudioDecoder.toWav(file)
            assertEquals("RIFF", output.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertTrue(output.size > 44)
            assertEquals(16000, ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).getInt(24))
        } finally { file.delete() }
    }

    @Test fun malformedAudioFailsExplicitly() = runBlocking {
        val file = File.createTempFile("media-invalid-", ".wav", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("not audio")
            var failed = false
            try { MediaAudioDecoder.toWav(file) } catch (_: java.io.IOException) { failed = true }
            catch (_: IllegalArgumentException) { failed = true }
            catch (_: IllegalStateException) { failed = true }
            assertTrue("Invalid files must not become empty successful transcripts", failed)
        } finally { file.delete() }
    }
}
