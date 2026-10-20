package com.zero.v2.services.security

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CredentialStorageGateTest {
    @Test fun keystoreFailureDoesNotWriteAndRetryRecovers() {
        val data = mutableMapOf("existing" to "preserved")
        var unavailable = true
        val gate = CredentialStorageGate { check(!unavailable); preferences(data) }
        try { gate.access { it.edit().putString("new", "secret").commit() }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(mapOf("existing" to "preserved"), data)
        assertNotNull(gate.error)
        unavailable = false
        gate.access { assertTrue(it.edit().putString("new", "secret").commit()) }
        assertEquals("secret", gate.access { it.getString("new", null) })
        assertNull(gate.error)
    }

    @Test fun migrationActivatesOnlyVerifiedCopyAndKeepsLegacyOnFailure() {
        val legacy = mapOf("key" to "preserved")
        val destination = mutableMapOf<String, String>()
        var activated = false
        try {
            migrateLegacyCredentials(legacy, preferences(destination, false)) { activated = true; true }
            fail()
        } catch (_: IllegalStateException) { }
        assertFalse(activated)
        assertEquals("preserved", legacy["key"])
        migrateLegacyCredentials(legacy, preferences(destination)) { activated = true; true }
        assertTrue(activated)
        assertEquals(legacy, destination)
        assertEquals("preserved", legacy["key"])
    }

    private fun preferences(data: MutableMap<String, String>, accepted: Boolean = true): SharedPreferences =
        Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> data[args!![0]] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, String>()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { editor, op, values ->
                        when (op.name) {
                            "putString" -> { pending[values!![0] as String] = values[1] as String; editor }
                            "commit" -> { data.putAll(pending.takeIf { accepted }.orEmpty()); accepted }
                            else -> error(op.name)
                        }
                    }
                }
                else -> error(method.name)
            }
        } as SharedPreferences
}
