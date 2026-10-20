package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly requested recovery after a temporary thermal pause, never part of ordinary regression. */
class TemporaryModuleResumeAcceptanceTest {
    @Test fun resumeQqThroughItsProductionOwner() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("resumeTemporaryModule") == "qqbot")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val provider = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val before = requireNotNull(provider.database().dynamicModuleDao().getById("qqbot"))
        assertTrue(entry.termux().ensureSupervisor())
        entry.lifecycleManager().safeCall(entry.mcp(), "temporary-resume-init") { entry.mcp().onStart() }
        assertEquals(true, entry.lifecycleManager().safeCall(entry.mcp(), "temporary-qq-resume") {
            entry.mcp().mountDynamicModule(before)
        })
        val after = requireNotNull(provider.database().dynamicModuleDao().getById("qqbot"))
        assertEquals("ACTIVE", after.state)
        assertTrue("Recovery must retain module source", before.source == after.source)
        assertEquals(before.crashCount, after.crashCount)
    }
}
