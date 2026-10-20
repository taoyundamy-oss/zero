package com.zero.v2.app.acceptance

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

/** Actual screen and OS picker; host cancels via bounded real BACK, no personal grant revocation. */
class WorkFilesPickerAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun cancelReselectAndActualMarkdownCallbacksPreserveDirectory() = runBlocking {
        val context = instrumentation.targetContext
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val repository = entry.workDirectory()
        val original = requireNotNull(repository.currentDirUri())
        val preferences = context.getSharedPreferences("zero_work_dir", android.content.Context.MODE_PRIVATE)
        val originalPreferences = preferences.all.toMap()
        val originalGrants = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val rootDocument = android.provider.DocumentsContract.buildDocumentUriUsingTree(original,
            android.provider.DocumentsContract.getTreeDocumentId(original))
        val rootName = context.contentResolver.query(rootDocument,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("pickerRunId"))
        val signal = File(context.filesDir, "a8-picker-back.json")
        signal.writeText(JSONObject().put("runId", runId).put("requestBack", false).toString())
        val folder = "a8_acceptance_picker_" + java.util.UUID.randomUUID().toString()
        assertFalse(repository.listFiles().any { it.name == folder })
        val body = "# 目录验收\n\n只包含本次合成资料。\n\nPICKER_DOCUMENT_END"
        var actualPath: String? = null
        var activity: WorkFilesAcceptanceActivity? = null
        fun savedCount() = entry.workTraces().getTrace("ui_work_files").count { it.summary == "dir_saved: ok=true" }
        val initialSavedCount = savedCount()
        try {
            val written = repository.writeText("$folder/readme.md", body, append = false)
            assertNull(written.error); actualPath = written.finalRelPath
            activity = instrumentation.startActivitySync(Intent(context, WorkFilesAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WorkFilesAcceptanceActivity
            waitUntil { !activity!!.model.state.value.busy && nodes().any { it.text?.toString() == "更换目录" } }
            assertEquals(rootDocument, activity!!.model.pickerInitialUri())
            click("更换目录")
            waitUntil { automation.rootInActiveWindow?.packageName?.toString() == "com.google.android.documentsui" }
            signal.writeText(JSONObject().put("runId", runId).put("requestBack", true)
                .put("nonce", java.util.UUID.randomUUID().toString()).toString())
            waitUntil { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
            signal.writeText(JSONObject().put("runId", runId).put("requestBack", false).toString())
            assertEquals(originalPreferences, preferences.all)
            assertEquals(initialSavedCount, savedCount())
            click("更换目录")
            waitUntil { automation.rootInActiveWindow?.packageName?.toString() == "com.google.android.documentsui" }
            waitUntil { nodes().any { it.text?.toString() == rootName } }
            clickAny(setOf("使用此文件夹", "USE THIS FOLDER", "Use this folder"))
            var allowed = false
            waitUntil {
                if (automation.rootInActiveWindow?.packageName?.toString() == context.packageName) true
                else {
                    if (!allowed) {
                        val candidate = nodes().firstOrNull { it.text?.toString() in setOf("允许", "ALLOW", "Allow") }
                        if (candidate != null) { assertTrue(owner(candidate).performAction(AccessibilityNodeInfo.ACTION_CLICK)); allowed = true }
                    }
                    false
                }
            }
            waitUntil { savedCount() == initialSavedCount + 1 && !activity!!.model.state.value.busy }
            assertEquals(original, repository.currentDirUri())
            assertEquals(originalPreferences, preferences.all)
            val grant = context.contentResolver.persistedUriPermissions.single { it.uri == original }
            assertTrue(grant.isReadPermission && grant.isWritePermission)
            reachDescription("打开 $folder")
            click("打开 $folder", description = true)
            waitUntil { activity!!.model.state.value.directoryPath == folder && !activity!!.model.state.value.busy }
            click("打开 ${actualPath!!.substringAfterLast('/')}", description = true)
            waitUntil { activity!!.model.state.value.documentText == body && nodes().any { it.text?.toString() == "PICKER_DOCUMENT_END" } }
            // Accessibility becomes ready before the platform dialog fade completes.
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            val bitmap = requireNotNull(automation.takeScreenshot())
            File(context.filesDir, "a8-picker-document.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
            click("关闭")
            waitUntil { activity!!.model.state.value.documentName == null }
            click("返回", description = true)
            waitUntil { activity!!.model.state.value.directoryPath.isEmpty() && !activity!!.model.state.value.busy }
            File(context.filesDir, "a8-picker-report.json").writeText(JSONObject()
                .put("cancelPreservedPreferences", true).put("realGrantCallback", true)
                .put("sameDirectoryReselected", true).put("persistentReadWrite", true)
                .put("actualScreenDirectoryAndMarkdown", true).put("parentRestored", true)
                .put("providerRequests", 0).put("passed", true).toString(2))
        } finally {
            signal.writeText(JSONObject().put("runId", runId).put("requestBack", false).toString())
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            if (repository.currentDirUri() != original) assertTrue(repository.saveDirectory(original))
            context.contentResolver.persistedUriPermissions.filter { it.uri !in originalGrants }.forEach {
                val flags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                context.contentResolver.releasePersistableUriPermission(it.uri, flags)
            }
            actualPath?.let { path ->
                assertEquals(listOf(path.substringAfterLast('/')), repository.listFiles(folder).map { it.name })
                assertArrayEquals(body.toByteArray(), repository.readBinary(path))
                assertNull(repository.deleteFile(path))
            }
            if (repository.listFiles().any { it.name == folder }) {
                assertTrue(repository.listFiles(folder).isEmpty())
                assertNull(repository.deleteTree(folder, force = false).error)
            }
            assertFalse(repository.listFiles().any { it.name == folder })
            assertEquals(originalPreferences, preferences.all)
        }
    }

    private fun click(label: String, description: Boolean = false) {
        waitUntil { nodes().any { if (description) it.contentDescription?.toString() == label else it.text?.toString() == label } }
        val target = nodes().first { if (description) it.contentDescription?.toString() == label else it.text?.toString() == label }
        assertTrue(owner(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync()
    }
    private fun clickAny(labels: Set<String>) {
        waitUntil { nodes().any { it.text?.toString() in labels } }
        assertTrue(owner(nodes().first { it.text?.toString() in labels }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun owner(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) { current = requireNotNull(current.parent); current.refresh() }
        assertTrue(current.isEnabled); return current
    }
    private fun reachDescription(label: String) {
        repeat(20) {
            val tree = nodes()
            if (tree.any { it.contentDescription?.toString() == label && it.isVisibleToUser }) return
            val scroll = tree.firstOrNull { it.isScrollable && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            assertTrue(requireNotNull(scroll).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); Thread.sleep(200)
        }
        fail("Synthetic directory row not reachable")
    }
    private fun waitUntil(predicate: () -> Boolean) {
        repeat(150) { if (predicate()) return; Thread.sleep(100) }
        throw AssertionError("WorkFiles actual UI/picker condition not reached")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            out.add(node); for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow); return out
    }
}
