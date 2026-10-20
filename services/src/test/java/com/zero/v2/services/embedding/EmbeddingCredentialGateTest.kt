package com.zero.v2.services.embedding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingCredentialGateTest {
    @Test fun rejectedCredentialStaysPausedButRotationOrEndpointChangeReopens() {
        val gate = EmbeddingCredentialGate()
        val first = gate.stamp("endpoint/model", "fixture-key-one")
        assertTrue(gate.permits(first))
        gate.reject(first)
        assertFalse(gate.permits(gate.stamp("endpoint/model", "fixture-key-one")))
        assertTrue(gate.permits(gate.stamp("endpoint/model", "fixture-key-two")))
        assertTrue(gate.permits(gate.stamp("other/model", "fixture-key-one")))
        assertFalse(gate.permits(null))
        assertFalse(first!!.contains("fixture-key"))
    }
}
