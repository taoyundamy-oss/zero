package com.zero.v2.framework.chat

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/** Atomic private metadata. No message text, API credentials or provider reasoning is stored here. */
@Singleton
class GenerationProjectionStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun file(conversation: String): AtomicFile {
        val directory = File(context.noBackupFilesDir, "request-generations").apply { mkdirs() }
        return AtomicFile(File(directory, GenerationProjection.digest(conversation) + ".properties"))
    }

    @Synchronized internal fun load(conversation: String): GenerationProjection {
        val state = Properties()
        try { file(conversation).openRead().use { state.load(it) } }
        catch (_: java.io.IOException) { state.clear() }
        catch (_: IllegalArgumentException) { state.clear() }
        return GenerationProjection(state)
    }

    @Synchronized internal fun save(conversation: String, projection: GenerationProjection) {
        val target = file(conversation)
        val output = target.startWrite()
        try {
            projection.snapshot().store(output, "request generation v1")
            target.finishWrite(output)
        } catch (failure: Exception) {
            target.failWrite(output)
            throw failure
        }
    }
}
