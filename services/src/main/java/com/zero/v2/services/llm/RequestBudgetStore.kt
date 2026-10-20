package com.zero.v2.services.llm

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** Bounded private hash/token metadata; no prompts, provider state or credentials. */
class RequestBudgetStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "request-input-budget-v1.json"))

    fun read(): String? = try {
        file.openRead().use(::readBounded)
    } catch (_: java.io.IOException) { null }

    private fun readBounded(stream: java.io.InputStream): String? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun write(value: String) {
        if (value.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return
        try {
            val output = file.startWrite()
            try {
                output.write(value.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (failure: java.io.IOException) {
                file.failWrite(output)
                throw failure
            }
        } catch (_: java.io.IOException) {
            Log.w("RequestBudgetStore", "budget_metadata_write_failed; memory observation retained")
        }
    }

    private companion object {
        const val MAX_BYTES = 3 * 1024 * 1024
        const val BUFFER_BYTES = 8192
    }
}
