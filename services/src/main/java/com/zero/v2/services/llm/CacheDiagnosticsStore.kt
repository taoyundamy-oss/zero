package com.zero.v2.services.llm

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Private diagnostic hashes only. Failure never blocks an inference request. */
class CacheDiagnosticsStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "cache-continuity-v1.json"))

    fun read(): String? = try {
        file.openRead().use(::readBounded)
    } catch (_: IOException) { null }

    private fun readBounded(input: java.io.InputStream): String? {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) return null
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
    }

    fun write(value: String): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return false
        return try {
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (failure: IOException) {
                file.failWrite(output)
                throw failure
            }
            true
        } catch (_: IOException) { false }
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val BUFFER_SIZE = 8192
    }
}
