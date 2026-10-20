package com.zero.v2.app.ui.settings

internal enum class WorkFileDestination { DIRECTORY, TEXT, EXTERNAL }

internal fun workFileDestination(name: String, isDirectory: Boolean): WorkFileDestination =
    when {
        isDirectory -> WorkFileDestination.DIRECTORY
        name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "txt") -> WorkFileDestination.TEXT
        else -> WorkFileDestination.EXTERNAL
    }
