package com.zero.v2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelativePathPolicyTest {
    @Test
    fun trimsAndSplitsSafeRelativePath() {
        assertEquals(listOf("docs", "note.md"), RelativePathPolicy.segments(" docs / note.md "))
        assertEquals(emptyList<String>(), RelativePathPolicy.segments(" / ", allowEmpty = true))
    }

    @Test
    fun rejectsTraversalAndMissingLeaf() {
        assertNull(RelativePathPolicy.segments("../secret"))
        assertNull(RelativePathPolicy.segments("."))
        assertNull(RelativePathPolicy.segments("/"))
    }
}
