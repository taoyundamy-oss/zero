package com.zero.v2.services.security

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class ConfirmedPreferenceWriteTest {
    @Test fun rejectedPutRemoveAndNewKeyRestoreMemoryAndKeepDisk() {
        val backend = Backend()
        backend.reject = true
        for (value in listOf("new", null)) {
            try { writePreferenceConfirmed(backend.prefs, "owned", value, "rejected"); fail("Must reject") }
            catch (_: IllegalStateException) { }
            assertEquals("old", backend.memory["owned"])
            assertEquals("old", backend.disk["owned"])
        }
        try { writePreferenceConfirmed(backend.prefs, "new", "candidate", "rejected"); fail("Must reject") }
        catch (_: IllegalStateException) { }
        assertFalse(backend.memory.containsKey("new"))
        assertFalse(backend.disk.containsKey("new"))
        backend.reject = false
        writePreferenceConfirmed(backend.prefs, "owned", "retry", "rejected")
        assertEquals("retry", backend.disk["owned"])
        writePreferenceConfirmed(backend.prefs, "owned", null, "rejected")
        assertFalse(backend.disk.containsKey("owned"))
    }

    private class Backend {
        var reject = false
        val memory = mutableMapOf("owned" to "old")
        var disk = memory.toMap()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> memory[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error(method.name)
            }
        } as SharedPreferences

        fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { pending[args!![0] as String] = args[1] as String?; proxy }
                    "remove" -> { pending[args!![0] as String] = null; proxy }
                    "commit" -> {
                        pending.forEach { (key, value) ->
                            if (value == null) memory.remove(key) else memory[key] = value
                        }
                        if (!reject) disk = memory.toMap()
                        !reject
                    }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
        }
    }
}
