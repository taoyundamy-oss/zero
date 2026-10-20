package com.zero.v2.app.acceptance

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.background.restoreAutonomousHost
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.store.acceptanceConfigStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Production boot decision with confirmed isolated Room configuration; service start is intercepted. */
class BootHostPolicyAcceptanceTest {
    @Test fun confirmedModesAndDisabledRuntimeControlBootHostWithoutRewritingConfiguration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = acceptanceConfigStore(db.moduleConfigDao(), scope)
        var starts = 0
        try {
            for (mode in listOf("SMART", "ALWAYS_ON", "OFF")) {
                val config = "{\"mode\":\"$mode\",\"selfDriveOnTick\":true}"
                store.saveConfirmed("agent_behavior", config)
                val before = starts
                assertEquals(mode != "OFF", restoreAutonomousHost(store, { true }) { starts++ })
                assertEquals(before + if (mode == "OFF") 0 else 1, starts)
                assertFalse(restoreAutonomousHost(store, { false }) { fail("Disabled runtime must not start") })
                assertEquals(config, store.get("agent_behavior"))
            }
            assertEquals(2, starts)
        } finally { scope.cancel(); delay(100); db.close() }
    }
}
