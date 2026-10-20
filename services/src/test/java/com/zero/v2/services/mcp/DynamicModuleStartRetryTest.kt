package com.zero.v2.services.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DynamicModuleStartRetryTest {
    private fun failed(reason: String) = JSONObject().put("ok", false).put("error", reason)
    @Test fun busyRequestsWaitUntilAcceptedWithBoundedDeadline() = runBlocking {
        var now = 0L
        var calls = 0
        val result = startDynamicModuleWithRetry(
            { calls++; if (calls < 4) failed("control_plane_busy") else JSONObject().put("ok", true) },
            { false }, { now }, { now += it })
        assertTrue(result!!.getBoolean("ok"))
        assertEquals(4, calls)
        calls = 0
        val exhausted = startDynamicModuleWithRetry(
            { calls++; failed("control_plane_busy") }, { false }, { now }, { now += it })
        assertEquals("control_plane_busy", exhausted!!.getString("error"))
        assertEquals(91, calls)
    }
    @Test fun transientFailureRetriesOnceButPermanentAndEmergencyDoNot() = runBlocking {
        for (failure in listOf(null, failed("health_check_failed"))) {
            var calls = 0
            val result = startDynamicModuleWithRetry(
                { calls++; if (calls == 1) failure else JSONObject().put("ok", true) }, { false }, pause = {})
            assertTrue(result!!.getBoolean("ok"))
            assertEquals(2, calls)
        }
        var calls = 0
        val invalid = startDynamicModuleWithRetry(
            { calls++; failed("runtime_contract_identity_mismatch") }, { false }, pause = {})
        assertEquals("runtime_contract_identity_mismatch", invalid!!.getString("error"))
        assertEquals(1, calls)
        val stopped = startDynamicModuleWithRetry({ calls++; null }, { true })
        assertEquals("emergency_stop", stopped!!.getString("error"))
        assertEquals(1, calls)
    }
    @Test fun cancelledRequestCannotRetryOrReturnSuccess() = runBlocking {
        var calls = 0
        try {
            startDynamicModuleWithRetry({ calls++; throw CancellationException("owned cancellation") }, { false })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }
}
