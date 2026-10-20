package com.zero.v2.services.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class AlarmSchedulingPolicyTest {
    @Test fun exactPermissionUsesOneExactSubmission() {
        val calls = mutableListOf<String>()
        val result = AlarmSchedulingPolicy.submit({ true }, { calls += "exact" }, { calls += "window" })
        assertEquals(AlarmSchedulingPolicy.Mode.EXACT, result.mode)
        assertEquals(listOf("exact"), calls)
    }

    @Test fun deniedPermissionUsesOneWindowSubmission() {
        val calls = mutableListOf<String>()
        val result = AlarmSchedulingPolicy.submit({ false }, { calls += "exact" }, { calls += "window" })
        assertEquals(AlarmSchedulingPolicy.Mode.WINDOW, result.mode)
        assertEquals(listOf("window"), calls)
    }

    @Test fun permissionRevokedBetweenCheckAndSubmissionFallsBackOnce() {
        val calls = mutableListOf<String>()
        val result = AlarmSchedulingPolicy.submit({ true }, {
            calls += "exact"
            throw SecurityException("Permission revoked")
        }, { calls += "window" })
        assertEquals(AlarmSchedulingPolicy.Mode.WINDOW, result.mode)
        assertEquals(listOf("exact", "window"), calls)
    }

    @Test fun permissionCheckSecurityFailureStillTriesWindow() {
        var windows = 0
        val result = AlarmSchedulingPolicy.submit({ throw SecurityException("ROM permission check") },
            { error("Must not submit exact") }, { windows++ })
        assertEquals(AlarmSchedulingPolicy.Mode.WINDOW, result.mode)
        assertEquals(1, windows)
    }

    @Test fun failedFallbackIsReportedWithoutRetryLoop() {
        var windows = 0
        val result = AlarmSchedulingPolicy.submit({ true }, { throw SecurityException() }, {
            windows++
            error("Service unavailable")
        })
        assertEquals(1, windows)
        assertEquals(AlarmSchedulingPolicy.Mode.FAILED, result.mode)
        assertEquals("IllegalStateException", result.reason)
    }

    @Test fun unrelatedFailureIsReportedWithoutPermissionFallback() {
        val result = AlarmSchedulingPolicy.submit({ true }, { throw IllegalArgumentException() },
            { error("Must not disguise a non-permission failure") })
        assertEquals(AlarmSchedulingPolicy.Mode.FAILED, result.mode)
        assertEquals("IllegalArgumentException", result.reason)
    }

    @Test fun rejectedWindowMustNotBeSubmittedTwice() {
        var windows = 0
        val result = AlarmSchedulingPolicy.submit({ false }, { error("No exact permission") }, {
            windows++
            throw SecurityException("Window also rejected")
        })
        assertEquals(1, windows)
        assertEquals(AlarmSchedulingPolicy.Mode.FAILED, result.mode)
        assertEquals("SecurityException", result.reason)
    }

    @Test fun cancellationPropagatesWithoutFallback() {
        var cancelled = false
        try {
            AlarmSchedulingPolicy.submit({ true }, { throw CancellationException("cancelled") },
                { error("Must not retry cancellation") })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
