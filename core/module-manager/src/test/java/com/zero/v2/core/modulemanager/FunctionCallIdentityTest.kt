package com.zero.v2.core.modulemanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionCallIdentityTest {

    @Test
    fun `explicit model call id is preserved and derives one trace id`() {
        val identity = FunctionCallIdentity.create("call_model_42")

        assertEquals("call_model_42", identity.callId)
        assertEquals("fc_call_model_42", identity.traceId)
    }

    @Test
    fun `blank call id gets a unique generated id`() {
        val first = FunctionCallIdentity.create("")
        val second = FunctionCallIdentity.create(null)

        assertTrue(first.callId.startsWith("call_"))
        assertTrue(second.callId.startsWith("call_"))
        assertNotEquals(first.callId, second.callId)
        assertEquals("fc_${first.callId}", first.traceId)
        assertEquals("fc_${second.callId}", second.traceId)
    }
}
