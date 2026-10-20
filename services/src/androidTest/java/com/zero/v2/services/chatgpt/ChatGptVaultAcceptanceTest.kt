package com.zero.v2.services.chatgpt

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptVaultAcceptanceTest {
    @Test fun encryptedSnapshotReopensAndTamperingNeverReplacesOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zero.v2.siwcauth.test") { "Isolated test UID required" }
        val file = File(context.noBackupFilesDir, "chatgpt-accounts.enc")
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val store = ChatGptVault(context)
            val data = JSONObject().put("host", "synthetic-host").put("token", "SYNTHETIC_SECRET_84923")
            store.write(data)
            val bytes = file.readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("SYNTHETIC_SECRET_84923"))
            assertEquals(data.toString(), ChatGptVault(context).read().toString())
            val damaged = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(damaged)
            assertTrue(runCatching { ChatGptVault(context).read() }.isFailure)
            assertTrue(file.readBytes().contentEquals(damaged))
            file.writeBytes(bytes)
            keys.deleteEntry("zero_chatgpt_accounts_v1")
            assertTrue(runCatching { ChatGptVault(context).read() }.isFailure)
            assertTrue(file.readBytes().contentEquals(bytes))
            assertFalse(keys.containsAlias("zero_chatgpt_accounts_v1"))
        } finally {
            file.delete()
            keys.deleteEntry("zero_chatgpt_accounts_v1")
        }
    }
}
