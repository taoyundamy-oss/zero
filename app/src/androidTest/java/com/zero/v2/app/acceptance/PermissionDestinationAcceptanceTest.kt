package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.openPermissionDestination
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PermissionDestinationAcceptanceTest {
    private fun fixture(failures: List<RuntimeException?>, block: (ContextWrapper, MutableList<String?>) -> Unit) {
        val attempts = mutableListOf<String?>()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) {
                attempts.add(intent.action)
                failures[attempts.size - 1]?.let { throw it }
            }
        }
        block(context, attempts)
    }
    @Test fun successfulPrimaryDoesNotOpenFallback() = fixture(listOf(null)) { context, attempts ->
        assertTrue(openPermissionDestination(context, Intent("primary"), Intent("fallback")))
        assertEquals(listOf("primary"), attempts)
    }
    @Test fun rejectedPrimaryUsesOneFallback() = fixture(listOf(SecurityException(), null)) { context, attempts ->
        assertTrue(openPermissionDestination(context, Intent("primary"), Intent("fallback")))
        assertEquals(listOf("primary", "fallback"), attempts)
    }
    @Test fun bothPagesRejectedReturnVisibleFailureSignal() = fixture(listOf(SecurityException(), android.content.ActivityNotFoundException())) { context, attempts ->
        assertFalse(openPermissionDestination(context, Intent("primary"), Intent("fallback")))
        assertEquals(2, attempts.size)
    }
    @Test fun cancellationDoesNotTryFallback() {
        val cancelled = CancellationException("isolated launch cancellation")
        fixture(listOf(cancelled)) { context, attempts ->
            try { openPermissionDestination(context, Intent("primary"), Intent("fallback")); fail("Cancellation must propagate") }
            catch (e: CancellationException) { assertSame(cancelled, e) }
            assertEquals(1, attempts.size)
        }
    }
}
