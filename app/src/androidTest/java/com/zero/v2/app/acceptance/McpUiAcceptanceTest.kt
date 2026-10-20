package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.*
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.services.mcp.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpUiAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null || !n.refresh()) return
            all += n
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow)
        return all
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(100) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(60)
        }
        error("Owned MCP UI control missing")
    }
    private fun click(text: String) {
        var node = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        inst.waitForIdleSync()
    }
    private fun set(old: String, value: String) {
        assertTrue(find { it.isEditable && it.text?.toString() == old }.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
        inst.waitForIdleSync()
    }

    @Test fun productionPageAndEditorResourceWindowMatrix() {
        val split = InstrumentationRegistry.getArguments().getString("mcpWindow") == "split"
        val rows = JSONArray()
        val root = java.io.File(inst.targetContext.filesDir, if (split) "a8-s4-ui-split" else "a8-s4-ui-wide")
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            OwnedUi().use { owned ->
                val activity = owned.launch(scale, dark, dynamic)
                try {
                    if (split) SplitWindowAcceptance.ensure(activity)
                    val width = activity.resources.configuration.screenWidthDp
                    assertTrue(if (split) activity.isInMultiWindowMode && width in 280..599 else width >= 600)
                    assertEquals(scale, activity.resources.configuration.fontScale, .01f)
                    find { it.text?.toString() == "添加连接" }
                    assertTrue("Paired text/surface contrast must reach 4.5", owned.contrast >= 4.5)
                    val folder = java.io.File(root, "$scale-$dark-$dynamic").apply { mkdirs() }
                    fun shot(name: String) {
                        Thread.sleep(100)
                        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
                        java.io.File(folder, "$name.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    shot("list")
                    click("编辑")
                    find { it.isEditable && it.text?.toString() == owned.server.name }
                    shot("editor")
                    repeat(12) {
                        nodes().firstOrNull { it.isScrollable }
                            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        Thread.sleep(80)
                    }
                    val tail = find { it.text?.toString() == "保存连接和发现工具分别显示结果；发现失败可稍后重试。" }
                    val tailBounds = android.graphics.Rect().also(tail::getBoundsInScreen)
                    val saveBounds = android.graphics.Rect().also(
                        find { it.text?.toString() == "保存连接" }::getBoundsInScreen,
                    )
                    assertTrue("Last form explanation must be above fixed save footer", tailBounds.bottom <= saveBounds.top)
                    shot("editor-tail")
                    click("保存连接")
                    find { it.text?.toString() == "添加连接" }
                    assertEquals(owned.server.name, owned.store.servers.value.single().name)
                    click("删除")
                    find { it.text?.toString() == "删除连接？" }
                    assertEquals(1, owned.store.servers.value.size)
                    shot("delete-confirmation")
                    click("取消")
                    assertEquals(1, owned.store.servers.value.size)
                    rows.put(JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic)
                        .put("widthDp", width).put("heightDp", activity.resources.configuration.screenHeightDp)
                        .put("multiWindow", activity.isInMultiWindowMode).put("saved", true).put("deleteGuard", true)
                        .put("contrast", owned.contrast).put("editorTailReachable", true))
                } finally { inst.runOnMainSync { activity.finish() } }
            }
        }
        root.mkdirs()
        java.io.File(root, "matrix.json").writeText(rows.toString(2))
    }

    @Test fun rejectedSaveKeepsDraftAndReturnRequiresConfirmation() {
        OwnedUi().use { owned ->
            val activity = owned.launch(1f, false, false)
            try {
                click("编辑")
                set(owned.server.name, "Owned changed draft")
                owned.backend.reject = true
                click("保存连接")
                find { it.text?.toString()?.contains("保存失败") == true }
                assertEquals(owned.server.name, owned.store.servers.value.single().name)
                click("返回")
                find { it.text?.toString() == "放弃未保存的修改？" }
                click("继续编辑")
                find { it.isEditable && it.text?.toString() == "Owned changed draft" }
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                find { it.text?.toString() == "放弃未保存的修改？" }
                click("继续编辑")
                owned.backend.reject = false
                click("保存连接")
                find { it.text?.toString() == "添加连接" }
                assertEquals("Owned changed draft", owned.store.servers.value.single().name)
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }

    @Test fun activityRecreationDoesNotRestoreSensitiveQueryDraft() {
        OwnedUi().use { owned ->
            var activity = owned.launch(1f, false, false)
            try {
                click("编辑")
                set(owned.server.url, "https://owned.example/mcp?token=owned-sensitive-query")
                val monitor = inst.addMonitor(McpUiAcceptanceActivity::class.java.name, null, false)
                try {
                    inst.runOnMainSync { activity.recreate() }
                    activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10_000)) as McpUiAcceptanceActivity
                } finally { inst.removeMonitor(monitor) }
                find { it.isEditable && it.text?.toString() == owned.server.url }
                assertTrue(nodes().none { it.text?.toString()?.contains("owned-sensitive-query") == true })
                assertEquals(owned.server.url, owned.store.servers.value.single().url)
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }

    @Test fun activityRecreationKeepsPlainDraftAndDropsUnsavedKey() {
        OwnedUi().use { owned ->
            var activity = owned.launch(1f, false, false)
            try {
                click("编辑")
                set(owned.server.name, "Owned restored draft")
                assertTrue(find { it.isEditable && it.text.isNullOrEmpty() }.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "owned-unsaved-key")
                    }))
                val monitor = inst.addMonitor(McpUiAcceptanceActivity::class.java.name, null, false)
                try {
                    inst.runOnMainSync { activity.recreate() }
                    activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10_000)) as McpUiAcceptanceActivity
                } finally { inst.removeMonitor(monitor) }
                find { it.isEditable && it.text?.toString() == "Owned restored draft" }
                find { it.isEditable && it.text.isNullOrEmpty() }
                assertTrue(nodes().none { it.text?.toString()?.contains("owned-unsaved-key") == true })
                click("保存连接")
                find { it.text?.toString() == "添加连接" }
                assertEquals("Owned restored draft", owned.store.servers.value.single().name)
                assertEquals("", owned.store.servers.value.single().apiKeyRef)
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }

    private inner class OwnedUi : AutoCloseable {
        val backend = RejectingPreferences("[]")
        val context = object : ContextWrapper(inst.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = backend.preferences
        }
        val entry = EntryPointAccessors.fromApplication(context, ProviderAcceptanceEntryPoint::class.java)
        val store = McpServerStore(context, entry.traces())
        val server = McpServerConfig("owned_ui", "独立长名称连接 · 生产页面、失败重试和删除确认的统一验收", "https://owned.example/long/mcp/endpoint")
        val keys = OwnedCredentials()
        val owner = ViewModelStore()
        lateinit var vm: McpServersViewModel
        @Volatile var contrast = 0.0
        init {
            store.upsert(server)
            inst.runOnMainSync {
                vm = McpServersViewModel(store, keys, object : McpConnectionActions {
                    override val discoveredCount = MutableStateFlow(0)
                    override suspend fun refresh() = McpRefreshReport(0)
                })
                owner.put("owned", vm)
            }
        }
        fun launch(scale: Float, dark: Boolean, dynamic: Boolean): McpUiAcceptanceActivity {
            McpUiAcceptanceActivity.scale = scale
            McpUiAcceptanceActivity.content = {
                ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect {
                        contrast = minOf(androidx.core.graphics.ColorUtils.calculateContrast(
                            scheme.onSurface.toArgb(), scheme.surface.toArgb()),
                            androidx.core.graphics.ColorUtils.calculateContrast(
                                scheme.onSurfaceVariant.toArgb(), scheme.surface.toArgb()))
                    }
                    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        McpServersScreen({}, vm)
                    }
                }
            }
            return inst.startActivitySync(Intent(inst.targetContext, McpUiAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as McpUiAcceptanceActivity
        }
        override fun close() {
            inst.runOnMainSync { owner.clear() }
            McpUiAcceptanceActivity.content = null
            McpUiAcceptanceActivity.scale = 1f
        }
    }
}
