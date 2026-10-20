package com.zero.v2.services.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

internal interface ChatGptStorage {
    fun read(): JSONObject
    fun write(value: JSONObject)
}

/** Entire account snapshot is encrypted and atomically replaced; never part of Android or Zero backup. */
@Singleton
class ChatGptVault @Inject constructor(@ApplicationContext context: Context) : ChatGptStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-accounts.enc"))
    private val alias = "zero_chatgpt_accounts_v1"
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            "ChatGPT credential key unavailable; original retained"
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    @Synchronized override fun read(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject()
        val bytes = file.readFully()
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8))
    }

    @Synchronized override fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (failure: java.io.IOException) {
            file.failWrite(out)
            throw failure
        }
    }
    companion object {
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
