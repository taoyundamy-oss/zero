package com.zero.v2.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkFileOpenPolicyTest {
    @Test fun `directories open inside the workspace even with a file extension`() {
        assertEquals(WorkFileDestination.DIRECTORY, workFileDestination("notes.md", true))
    }

    @Test fun `markdown and text need no external viewer`() {
        listOf("README.MD", "笔记.markdown", "notes.txt").forEach {
            assertEquals(WorkFileDestination.TEXT, workFileDestination(it, false))
        }
        assertEquals(WorkFileDestination.EXTERNAL, workFileDestination("image.png", false))
    }
}
