package com.zero.v2.services.llm

import java.io.File

/** Debug-only access to the actual codecs; absent from release artifacts. */
object MediaDecodingAcceptanceAccess {
    suspend fun audio(file: File): ByteArray = MediaAudioDecoder.toWav(file)
    suspend fun video(file: File): List<ByteArray> = MediaVideoFrames.read(file)
}
