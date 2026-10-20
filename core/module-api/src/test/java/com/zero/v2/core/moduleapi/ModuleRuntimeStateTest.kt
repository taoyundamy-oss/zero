package com.zero.v2.core.moduleapi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleRuntimeStateTest {
    @Test
    fun `enabled module remains operational before startup`() {
        assertTrue(ModuleRuntimeState(true, ModuleLifecycle.UNINITIALIZED).operational)
        assertTrue(ModuleRuntimeState(true, ModuleLifecycle.AWAKE).operational)
        assertTrue(ModuleRuntimeState(true, ModuleLifecycle.STARTED).operational)
    }

    @Test
    fun `disabled and destroyed lifecycle are never operational`() {
        assertFalse(ModuleRuntimeState(false, ModuleLifecycle.STARTED).operational)
        assertFalse(ModuleRuntimeState(true, ModuleLifecycle.DISABLED).operational)
        assertFalse(ModuleRuntimeState(true, ModuleLifecycle.DESTROYED).operational)
    }
}
