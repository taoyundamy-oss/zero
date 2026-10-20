package com.zero.v2.app.data

/** Stage is real work, not a synthetic percentage. Writes/commit cannot be cancelled from UI. */
data class BackupProgress(val label: String, val cancellable: Boolean = true)
