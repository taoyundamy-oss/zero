package com.zero.v2.services.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Actual stop boundary callback: no network, tools or persistent state. */
class DynamicModuleStopConfirmationTest {
    @Test fun failedHttpOrDisconnectedResponseRetainsRegistration()=runBlocking {
        var changes=0
        assertFalse(confirmDynamicModuleStop(null) { changes++ })
        assertEquals(0,changes)
    }
    @Test fun explicitStopRejectionRetainsRegistration()=runBlocking {
        var changes=0
        assertFalse(confirmDynamicModuleStop(JSONObject().put("ok",false)) { changes++ })
        assertEquals(0,changes)
    }
    @Test fun malformedAcknowledgementRetainsRegistration()=runBlocking {
        for(reply in listOf(JSONObject(),JSONObject().put("ok","true"),JSONObject().put("error","isolated"))) {
            var changes=0
            assertFalse(confirmDynamicModuleStop(reply) { changes++ });assertEquals(0,changes)
        }
    }
    @Test fun explicitSuccessAppliesUnmountExactlyOnce()=runBlocking {
        var changes=0
        assertTrue(confirmDynamicModuleStop(JSONObject().put("ok",true)) { changes++ })
        assertEquals(1,changes)
    }
    @Test fun cancelledAcknowledgementDoesNotCommitRegistration()=runBlocking {
        val job=Job();var changes=0
        try {
            withContext(job) {
                job.cancel()
                confirmDynamicModuleStop(JSONObject().put("ok",true)) { changes++ }
            }
            fail("Must propagate cancellation")
        } catch (_:CancellationException) { assertEquals(0,changes) }
        finally { job.cancel() }
    }
}
