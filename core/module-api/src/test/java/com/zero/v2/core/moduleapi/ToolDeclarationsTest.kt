package com.zero.v2.core.moduleapi

import com.zero.v2.core.common.FunctionDefinition
import com.zero.v2.core.common.FunctionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolDeclarationsTest {
    @Test fun `collecting declarations has no handler side effects and preserves handler behavior`() = runBlocking {
        var calls = 0
        val tools = toolDeclarations("sample") {
            register(FunctionDefinition("sample_echo", "echo", emptyMap()), "sample") {
                calls++
                FunctionResult.success(it["text"].toString())
            }
        }
        assertEquals(0, calls)
        assertEquals("hi", tools.single().handler(mapOf("text" to "hi")).data)
        assertEquals(1, calls)
    }

    @Test fun `declarations cannot impersonate another module owner`() {
        assertThrows(IllegalArgumentException::class.java) {
            toolDeclarations("sample") {
                register(FunctionDefinition("sample_echo", "echo", emptyMap()), "other") {
                    FunctionResult.success("unused")
                }
            }
        }
    }
}
