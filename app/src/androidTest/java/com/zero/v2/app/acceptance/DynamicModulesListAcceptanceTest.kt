package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DynamicModulesContent
import com.zero.v2.app.ui.settings.DynamicModulesUiState
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.data.entity.DynamicModuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Actual production list/source and resource recreation; synthetic callbacks, no module changes. */
class DynamicModulesListAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null || !n.refresh()) return
            out.add(n)
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow)
        return out
    }
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(50) {
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return it }
            Thread.sleep(100)
        }
        error("Missing isolated module list/source: $label")
    }
    private fun owner(label: String): AccessibilityNodeInfo {
        var n = find(label)
        while (!n.isClickable) n = requireNotNull(n.parent)
        return n
    }
    private fun end() {
        repeat(100) {
            val n = nodes().firstOrNull { it.isScrollable } ?: error("Missing scroll viewport")
            if (n.actionList.none { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) return
            assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            Thread.sleep(80)
        }
        error("Scroll end unavailable")
    }
    private fun shot(folder: String, name: String) {
        Thread.sleep(500)
        val ui = inst.uiAutomation
        val root = requireNotNull(ui.rootInActiveWindow)
        assertEquals("com.zero.v2", root.packageName.toString())
        val bounds = Rect().also(ui.windows.first { it.id == root.windowId }::getBoundsInScreen)
        val bitmap = requireNotNull(ui.takeScreenshot())
        val crop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        try {
            val file = File(inst.targetContext.filesDir, "a8-dynamic-list/$folder/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { crop.recycle(); if (!bitmap.isRecycled) bitmap.recycle() }
    }
    @Test fun fullListAndSourceRestoreAcrossEightActualSplitConditions() {
        val report = org.json.JSONArray()
        val source = (1..32).joinToString("\n") { "# isolated source line $it" } + "\n# source tail marker"
        val modules = (1..16).map { index -> DynamicModuleEntity(
            id = "isolated_module_${index}_long_readable_name_for_narrow_window",
            source = "# isolated module $index\n" + source, createdAt = 1, state = when (index) {
                1 -> "ACTIVE"
                2 -> "CRASHED"
                3 -> "DISABLED"
                16 -> "STOPPED"
                else -> "ISOLATED_UNKNOWN"
            }) }
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            var activity = inst.startActivitySync(SplitWindowAcceptance.launchIntent(inst.targetContext)) as VisualAcceptanceActivity
            val toggles = java.util.concurrent.CopyOnWriteArrayList<String>()
            val details = java.util.concurrent.CopyOnWriteArrayList<String>()
            fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                val c = MaterialTheme.colorScheme
                for ((fg, bg) in listOf(c.onSurfaceVariant to c.surfaceVariant, c.onPrimaryContainer to c.primaryContainer,
                    c.onErrorContainer to c.errorContainer)) {
                    val a = fg.luminance(); val b = bg.luminance()
                    assertTrue((maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f) >= 4.5f)
                }
                DynamicModulesContent(DynamicModulesUiState(loading = false, modules = modules, toolCount = 16), {},
                    { details.add(it) },
                    { toggles.add(it) }, {}, {})
            } } } }
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale, activity.resources.configuration.fontScale, 0f)
                val density = activity.resources.displayMetrics.density
                val width = activity.resources.displayMetrics.widthPixels / density
                assertTrue(activity.isInMultiWindowMode && width in 280f..599f)
                mount(); find("已启用")
                val folder = "$scale-$dark-$dynamic"
                shot(folder, "list-head")
                end(); find(modules.last().id); find("已停止")
                for (label in listOf("详情与配置 ${modules.last().id}", "查看源码 ${modules.last().id}", "启停 ${modules.last().id}")) {
                    val n = owner(label); val rect = Rect().also(n::getBoundsInScreen)
                    assertTrue(rect.width() / density >= 48 && rect.height() / density >= 48)
                    assertTrue(SplitWindowAcceptance.bounds(activity).contains(rect))
                }
                shot(folder, "list-tail")
                assertTrue(owner("详情与配置 ${modules.last().id}").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(owner("启停 ${modules.last().id}").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertEquals(listOf(modules.last().id), details); assertEquals(listOf(modules.last().id), toggles)
                assertTrue(owner("查看源码 ${modules.last().id}").performAction(AccessibilityNodeInfo.ACTION_CLICK)); find("模块源码")
                end(); shot(folder, "source-tail")
                activity = SplitWindowAcceptance.recreate(activity, scale); mount()
                find("模块源码")
                assertTrue("Selected source must restore", nodes().any { it.isVisibleToUser && it.text?.toString() == modules.last().source })
                assertTrue("Source scroll must restore at end", nodes().first { it.isScrollable }.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                shot(folder, "source-restored")
                assertTrue(owner("关闭").performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200)
                assertTrue("List position must restore without scrolling", nodes().any { it.isVisibleToUser && it.text?.toString() == modules.last().id })
                report.put(org.json.JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic)
                    .put("widthDp", width).put("actualMultiWindow", true).put("sourceAndListRestored", true))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir, "a8-dynamic-list/report.json").writeText(org.json.JSONObject()
            .put("passed", true).put("conditions", report).put("scope", "production module list/source only")
            .put("providerRequests", 0).put("personalDataWrites", 0).toString(2))
    }
}
