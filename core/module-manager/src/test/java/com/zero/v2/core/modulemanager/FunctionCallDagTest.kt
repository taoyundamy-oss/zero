package com.zero.v2.core.modulemanager

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionCallDagTest {
    @Test
    fun `independent calls share first wave and dependent call is later`() {
        val calls = listOf(
            ToolCall("a", "search_a", "{}"),
            ToolCall("b", "search_b", "{}"),
            ToolCall("c", "synthesize", "{}")
        )
        val definitions = mapOf(
            "search_a" to FunctionDefinition("search_a", ""),
            "search_b" to FunctionDefinition("search_b", ""),
            "synthesize" to FunctionDefinition("synthesize", "", dependsOn = listOf("search_a"))
        )

        val result = planFunctionCallDag(calls, definitions)

        assertNull(result.error)
        assertEquals(listOf(listOf(0, 1), listOf(2)), result.plan?.waves)
    }

    @Test
    fun `missing dependency rejects the batch`() {
        val result = planFunctionCallDag(
            listOf(ToolCall("c", "synthesize", "{}")),
            mapOf("synthesize" to FunctionDefinition("synthesize", "", dependsOn = listOf("search")))
        )
        assertNull(result.plan)
        assertNotNull(result.error)
    }

    @Test
    fun `cyclic dependency rejects the batch`() {
        val calls = listOf(ToolCall("a", "a", "{}"), ToolCall("b", "b", "{}"))
        val definitions = mapOf(
            "a" to FunctionDefinition("a", "", dependsOn = listOf("b")),
            "b" to FunctionDefinition("b", "", dependsOn = listOf("a"))
        )
        val result = planFunctionCallDag(calls, definitions)
        assertNull(result.plan)
        assertEquals("工具调用依赖图存在环", result.error)
    }
}
