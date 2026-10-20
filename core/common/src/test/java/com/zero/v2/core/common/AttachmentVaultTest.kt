package com.zero.v2.core.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentVaultTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `attachment survives a new vault instance with stable reference`() {
        val root = temp.newFolder()
        val bytes = byteArrayOf(1, 2, 3)
        val saved = AttachmentVault(root).save(bytes)
        val reopened = AttachmentVault(root)
        assertArrayEquals(bytes, reopened.resolve(saved.toURI().toString()).readBytes())
        assertEquals(saved, reopened.save(bytes))
    }

    @Test fun `export refuses arbitrary app files and traversal`() {
        val vault = AttachmentVault(temp.newFolder())
        assertThrows(IllegalArgumentException::class.java) { vault.resolve(temp.newFile().toURI().toString()) }
        assertThrows(IllegalArgumentException::class.java) { vault.resolve("content://private/secret") }
    }
}
