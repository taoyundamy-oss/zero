package com.zero.v2.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaCompactorTest {
    @Test
    fun compact_removesOnlyEmptyNonSemanticFields() {
        val source = mapOf<String, Any?>(
            "name" to "web_search",
            "type" to "object",
            "description" to "",
            "required" to listOf("query"),
            "enum" to emptyList<String>(),
            "properties" to mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "",
                    "examples" to emptyList<String>(),
                ),
            ),
            "x-vendor-empty" to emptyMap<String, Any?>(),
        )

        val compacted = ToolSchemaCompactor.compact(source)
        assertEquals("web_search", compacted["name"])
        assertEquals("object", compacted["type"])
        assertEquals(listOf("query"), compacted["required"])
        assertTrue(compacted.containsKey("enum"))
        assertTrue(compacted.containsKey("properties"))
        assertFalse(compacted.containsKey("description"))
        assertFalse(compacted.containsKey("x-vendor-empty"))

        @Suppress("UNCHECKED_CAST")
        val query = (compacted["properties"] as Map<String, Any?>)["query"] as Map<String, Any?>
        assertEquals("string", query["type"])
        assertFalse(query.containsKey("description"))
        assertFalse(query.containsKey("examples"))
    }

    @Test
    fun compact_doesNotChangeSemanticKeysWhenValuesAreEmpty() {
        val source = mapOf<String, Any?>(
            "type" to "object",
            "required" to emptyList<String>(),
            "enum" to emptyList<String>(),
            "properties" to emptyMap<String, Any?>(),
            "items" to emptyMap<String, Any?>(),
        )

        val compacted = ToolSchemaCompactor.compact(source)
        assertEquals(source.keys, compacted.keys)
    }
}
