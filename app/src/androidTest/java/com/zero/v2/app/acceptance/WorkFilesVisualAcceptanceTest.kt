package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual page, isolated state callbacks: no personal SAF grants, deletion or cloud traffic. */
class WorkFilesVisualAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: VisualAcceptanceActivity

    @Test fun themesDirectoryReadingTailAndFailureStatesRemainUsable() {
        val context = instrumentation.targetContext
        activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val report = JSONArray()
        try {
            for (width in listOf(360, 720)) for (dark in listOf(false, true))
                for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                    val name = "$width-$dark-$dynamic-$scale"
                    setPage("workfiles", width, dark, dynamic, scale)
                    await { it.any { node -> node.text?.toString() == "工作文件" } }
                    val colors = requireNotNull(activity.palette)
                    assertTrue(contrast(colors.onSurface, colors.surfaceContainerLow) >= 4.5)
                    assertTrue(contrast(colors.onSurfaceVariant, colors.surfaceContainerLow) >= 4.5)
                    assertTrue(contrast(colors.error, colors.background) >= 4.5)
                    screenshot("$name-top")
                    click("打开 资料", description = true)
                    await { it.any { node -> node.text?.toString() == "工作目录/资料" } }
                    click("打开 验收.md", description = true)
                    await { it.any { node -> node.text?.toString() == "工作资料" } }
                    reach("WORK_DOCUMENT_END")
                    screenshot("$name-document-tail")
                    click("关闭")
                    await { it.any { node -> node.text?.toString() == "工作目录/资料" } }
                    click("返回", description = true)
                    await { it.any { node -> node.text?.toString() == "工作目录/" } }
                    reach("末行资料.md")
                    screenshot("$name-page-tail")
                    click("删除 末行资料.md", description = true)
                    await { it.any { node -> node.text?.toString() == "删除文件" } }
                    click("取消")
                    assertFalse(activity.workActions.any { it.startsWith("delete:") })
                    assertEquals(2, activity.workActions.count { it.startsWith("open:") })
                    report.put(JSONObject().put("name", name).put("directoryCallback", true)
                        .put("markdownTail", true).put("returnedToDirectory", true).put("deleteCancelled", true))
                }
            for (page in listOf("workfiles-empty", "workfiles-error", "workfiles-busy")) {
                setPage(page, 360, true, false, 2f)
                val term = if (page == "workfiles-empty") "选择工作目录" else "更换目录"
                val tree = await { it.any { node -> node.text?.toString() == term } }
                if (page == "workfiles-busy") {
                    val button = owner(tree.first { it.text?.toString() == term })
                    assertFalse(button.isEnabled)
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    settle(); assertTrue(activity.workActions.isEmpty())
                } else if (page == "workfiles-error") {
                    assertTrue(tree.any { it.text?.toString()?.contains("无法打开目录") == true })
                    click(term); assertEquals(listOf("choose"), activity.workActions.toList())
                } else {
                    assertTrue(tree.any { it.text?.toString()?.contains("设置工作目录后") == true })
                    click(term); assertEquals(listOf("choose"), activity.workActions.toList())
                }
                screenshot(page)
            }
            File(context.filesDir, "a8-workfiles/report.json").writeText(JSONObject().put("cases", report)
                .put("states", listOf("empty", "error", "busy")).put("providerRequests", 0)
                .put("scope", "production_page_with_isolated_callbacks_not_SAF_provider_or_persistent_grants").toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun setPage(page: String, width: Int, dark: Boolean, dynamic: Boolean, scale: Float) {
        instrumentation.runOnMainSync {
            activity.workActions.clear()
            activity.fixture = VisualAcceptanceActivity.Fixture(page, dark, dynamic, scale, width)
        }
        settle()
    }
    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble(); val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
    private fun owner(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) { current = requireNotNull(current.parent); current.refresh() }
        return current
    }
    private fun click(label: String, description: Boolean = false) {
        val tree = await { it.any { node -> if (description) node.contentDescription?.toString() == label else node.text?.toString() == label } }
        val target = owner(tree.first { if (description) it.contentDescription?.toString() == label else it.text?.toString() == label })
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        assertTrue("$label height $bounds", bounds.height() / activity.resources.displayMetrics.density >= 48)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle()
    }
    private fun reach(label: String) {
        repeat(24) {
            val tree = nodes()
            if (tree.any { it.isVisibleToUser && it.text?.toString() == label }) return
            val scroll = tree.firstOrNull { it.isScrollable && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            assertNotNull("No scroll for $label", scroll)
            assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); settle()
        }
        fail("Hidden tail: $label")
    }
    private fun await(predicate: (List<AccessibilityNodeInfo>) -> Boolean): List<AccessibilityNodeInfo> {
        repeat(50) { val tree = nodes(); if (predicate(tree)) return tree; Thread.sleep(100) }
        screenshot("failure")
        throw AssertionError("WorkFiles UI condition not reached")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            out.add(node); for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow); return out
    }
    private fun settle() { instrumentation.waitForIdleSync(); automation.waitForIdle(200, 5_000); Thread.sleep(150) }
    private fun screenshot(name: String) {
        val image = requireNotNull(automation.takeScreenshot())
        val file = File(instrumentation.targetContext.filesDir, "a8-workfiles/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
