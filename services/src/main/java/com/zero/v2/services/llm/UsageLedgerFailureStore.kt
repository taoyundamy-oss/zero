package com.zero.v2.services.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** A monotonic empty marker: contains no request, configuration, or credential data. */
class UsageLedgerFailureStore internal constructor(private val marker: File) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.noBackupFilesDir, "usage-ledger-incomplete"))

    fun failed(): Boolean = try { marker.exists() } catch (_: SecurityException) { true }

    fun mark() {
        try {
            marker.parentFile?.mkdirs()
            marker.createNewFile()
        } catch (_: java.io.IOException) {
            // The live warning remains set even when the entire storage device is unwritable.
        } catch (_: SecurityException) {
            // Never turn a diagnostic persistence failure into an inference failure.
        }
    }
}
