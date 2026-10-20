package com.zero.v2.services.agent

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleInitializationGateTest {
    @Test fun `cancelled waiter does not consume the shared readiness signal`() = runBlocking {
        val gate = ModuleInitializationGate()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady() }
        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        gate.complete()
        second.await()
        assertTrue(second.isCompleted)
        gate.awaitReady()
    }
}
