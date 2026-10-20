package com.zero.v2.core.moduleapi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleRuntimeStateStoreTest {
    @Test
    fun `state updates are broadcast with operational projection`() {
        val store = ModuleRuntimeStateStore(
            mapOf("demo" to ModuleRuntimeState(true, ModuleLifecycle.STARTED))
        )
        assertTrue(store.states.value.getValue("demo").operational)

        store.update("demo", ModuleRuntimeState(false, ModuleLifecycle.DISABLED))
        assertFalse(store.states.value.getValue("demo").operational)

        assertTrue(store.setUserEnabled("demo", true))
        assertFalse(store.states.value.getValue("demo").operational)
        store.update("demo", ModuleRuntimeState(true, ModuleLifecycle.STARTED))
        assertTrue(store.states.value.getValue("demo").operational)
    }

    @Test
    fun `unknown module cannot be enabled`() {
        val store = ModuleRuntimeStateStore(emptyMap())
        assertFalse(store.setUserEnabled("missing", true))
    }
}
