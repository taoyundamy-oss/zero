package com.zero.v2.app.acceptance

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.WorkFilesViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Two independent app processes; only an owned synthetic subdirectory may be written/deleted. */
class WorkFilesSafAcceptanceTest {
    @Test fun existingGrantDirectoryAndMarkdownSurviveProcessRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val directory = entry.workDirectory()
        val metadata = File(context.filesDir, "a8-workfiles-saf-fixture.json")
        val output = File(context.filesDir, "a8-workfiles-saf-report.json")
        val phase = InstrumentationRegistry.getArguments().getString("workFilesPhase") ?: "prepare"
        require(phase in setOf("prepare", "verify", "cleanup"))
        val tree = directory.currentDirUri()
        if (tree == null) {
            output.writeText(JSONObject().put("configured", false).put("phase", phase).put("passed", false).toString(2))
            fail("No configured SAF directory; fixture not created")
        }
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        assertTrue("Configured directory has no persistent read/write grant", grant?.isReadPermission == true && grant.isWritePermission)
        val treeHash = java.security.MessageDigest.getInstance("SHA-256").digest(tree.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val body = "# 工作文件验收\n\n这是隔离合成资料，不是个人记忆。\n\nSAF_DOCUMENT_END"
        if (phase == "cleanup") {
            val saved = JSONObject(metadata.readText())
            assertEquals(saved.getString("treeHash"), treeHash)
            val ownedFolder = saved.getString("folder")
            assertTrue(ownedFolder.startsWith("a8_acceptance_workfiles_"))
            assertTrue(directory.listFiles(ownedFolder).isEmpty())
            assertNull(directory.deleteTree(ownedFolder, force = true).error)
            assertFalse(directory.listFiles().any { it.name == ownedFolder })
            assertTrue(metadata.delete())
            return@runBlocking
        }
        var folder: String
        var path: String
        var initialPid: Int
        if (phase == "prepare") {
            assertFalse("Pending fixture must be verified before another is created", metadata.exists())
            folder = "a8_acceptance_workfiles_" + java.util.UUID.randomUUID().toString()
            assertFalse(directory.listFiles().any { it.name == folder })
            val result = directory.writeText("$folder/readme.md", body, append = false)
            assertNull(result.error)
            path = result.finalRelPath
            assertEquals(folder, path.substringBeforeLast('/'))
            initialPid = android.os.Process.myPid()
            metadata.writeText(JSONObject().put("folder", folder).put("path", path)
                .put("treeHash", treeHash).put("initialPid", initialPid).toString())
        } else {
            val saved = JSONObject(metadata.readText())
            assertEquals(saved.getString("treeHash"), treeHash)
            folder = saved.getString("folder"); path = saved.getString("path"); initialPid = saved.getInt("initialPid")
            assertTrue(folder.startsWith("a8_acceptance_workfiles_"))
            assertEquals(folder, path.substringBeforeLast('/'))
            assertNotEquals("Verification must run in a fresh app process", initialPid, android.os.Process.myPid())
        }
        val store = ViewModelStore()
        lateinit var model: WorkFilesViewModel
        var completed = false
        try {
            instrumentation.runOnMainSync {
                model = WorkFilesViewModel(directory, entry.systemEvents(), entry.workTraces())
                store.put("saf-work-files", model)
            }
            suspend fun ready() { withTimeout(10_000) { while (model.state.value.busy) delay(50) } }
            ready()
            assertTrue(model.state.value.dirConfigured)
            val child = model.state.value.files.single { it.name == folder }
            assertTrue(child.isDirectory)
            instrumentation.runOnMainSync { model.open(child) }; ready()
            assertEquals(folder, model.state.value.directoryPath)
            val document = model.state.value.files.single { it.name == path.substringAfterLast('/') }
            instrumentation.runOnMainSync { model.open(document) }; ready()
            assertEquals(body, model.state.value.documentText)
            assertArrayEquals(body.toByteArray(), directory.readBinary(path))
            instrumentation.runOnMainSync { model.closeDocument() }
            assertNull(model.state.value.documentText)
            instrumentation.runOnMainSync { model.parentDirectory() }; ready()
            assertEquals("", model.state.value.directoryPath)
            // Exact nonexistent name: error is visible rather than a crash or silently empty document.
            instrumentation.runOnMainSync { model.open(document.copy(name = "missing-${folder}.md")) }; ready()
            assertNotNull(model.state.value.error)
            assertNull(model.state.value.documentText)
            output.writeText(JSONObject().put("configured", true).put("persistentReadWriteGrant", true)
                .put("phase", phase).put("pid", android.os.Process.myPid()).put("initialPid", initialPid)
                .put("directoryOpened", true).put("markdownExact", true).put("missingFileVisible", true)
                .put("parentRestored", true).put("passed", true).put("providerRequests", 0).toString(2))
            completed = true
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            if (phase == "verify" || !completed) {
                // Never recursively remove an unexpected directory or unverified file.
                val children = directory.listFiles(folder)
                assertEquals(listOf(path.substringAfterLast('/')), children.map { it.name })
                assertArrayEquals(body.toByteArray(), directory.readBinary(path))
                assertNull(directory.deleteFile(path))
                assertTrue(directory.listFiles(folder).isEmpty())
                assertNull(directory.deleteTree(folder, force = true).error)
                assertFalse(directory.listFiles().any { it.name == folder })
                assertTrue(metadata.delete())
                if (completed) {
                    val report = JSONObject(output.readText()).put("fixtureRemoved", true)
                    output.writeText(report.toString(2))
                }
            }
        }
    }
}
