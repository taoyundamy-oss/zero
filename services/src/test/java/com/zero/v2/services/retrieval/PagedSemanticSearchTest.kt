package com.zero.v2.services.retrieval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedSemanticSearchTest {
    @Test fun `old semantic match beyond first page remains recallable with bounded working set`() = runBlocking {
        val rows = (1..1000).map { it.toString().padStart(4, '0') }
        var pages = 0
        val result = PagedSemanticSearch.search(
            limit = 3,
            page = { after, size ->
                pages++
                assertTrue(size <= 128)
                rows.filter { it > after }.take(size)
            },
            id = { it },
            score = { if (it == "0900") 1f else 0f },
        )
        assertEquals("0900", result.first())
        assertEquals(3, result.size)
        assertEquals(8, pages)
    }
}
