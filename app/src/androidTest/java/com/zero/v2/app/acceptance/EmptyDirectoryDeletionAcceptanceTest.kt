package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual SAF deletion boundary, confined to unique synthetic directories. */
class EmptyDirectoryDeletionAcceptanceTest {
    @Test fun missingAndRootPathsAreRejectedBeforeProviderAccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val prefName = "a8_guard_" + java.util.UUID.randomUUID().toString()
        val prefs = context.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("tree_uri", "content://invalid.a8.fixture/tree/owned").commit()
        val wrapper = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                if (name == "zero_work_dir") prefs else super.getSharedPreferences(name, mode)
        }
        val repository = com.zero.v2.app.data.WorkDirRepository(wrapper, entry.workTraces())
        try {
            for (force in listOf(false, true)) for (path in listOf("", " ", "/", "///", ".", "child/../")) {
                val result = repository.deleteTree(path, force)
                assertTrue(result.error.orEmpty().contains("不允许删除工作根目录或非法路径"))
                assertTrue(result.fileManifest.isEmpty() && result.failed.isEmpty())
            }
        } finally { assertTrue(context.deleteSharedPreferences(prefName)) }
    }

    @Test fun emptyAllowedNonemptyAndNestedRefusedWithoutForce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val repository = entry.workDirectory()
        assertTrue(repository.hasDirectory())
        val folder = "a8_acceptance_delete_" + java.util.UUID.randomUUID().toString()
        assertFalse(repository.listFiles().any { it.name == folder })
        val text = "Independent synthetic deletion boundary"
        var created = false
        try {
            val result = repository.writeText("$folder/probe.md", text, append = false)
            created = true
            assertNull(result.error)
            val refused = repository.deleteTree(folder, force = false)
            assertTrue(refused.refused)
            assertArrayEquals(text.toByteArray(), repository.readBinary(result.finalRelPath))
            assertNull(repository.deleteFile(result.finalRelPath))
            assertTrue(repository.listFiles(folder).isEmpty())
            val empty = repository.deleteTree(folder, force = false)
            File(context.filesDir, "a8-empty-directory-report.json").writeText(JSONObject()
                .put("nonemptyRefused", refused.refused).put("emptyRefused", empty.refused)
                .put("emptyFileCount", empty.fileCount).put("emptyDirCount", empty.dirCount)
                .put("emptyError", empty.error).toString(2))
            assertFalse("Empty directory wrongly treated as nonempty: dirs=${empty.dirCount}", empty.refused)
            assertNull(empty.error)
            assertFalse(repository.listFiles().any { it.name == folder })
            created = false
            val nested = repository.writeText("$folder/child/probe.md", text, append = false)
            created = true
            assertNull(nested.error)
            assertNull(repository.deleteFile(nested.finalRelPath))
            val nestedRefused = repository.deleteTree(folder, force = false)
            assertTrue("A child directory still requires recursive confirmation", nestedRefused.refused)
            assertEquals(1, repository.listFiles(folder).size)
            assertTrue(repository.listFiles("$folder/child").isEmpty())
            File(context.filesDir, "a8-empty-directory-report.json").writeText(JSONObject()
                .put("nonemptyRefused", true).put("emptyAllowed", true).put("nestedRefused", true)
                .put("providerRequests", 0).put("passed", true).toString(2))
        } finally {
            if (created) {
                assertTrue(folder.startsWith("a8_acceptance_delete_"))
                assertNull(repository.deleteTree(folder, force = true).error)
            }
            assertFalse(repository.listFiles().any { it.name == folder })
        }
    }
}
