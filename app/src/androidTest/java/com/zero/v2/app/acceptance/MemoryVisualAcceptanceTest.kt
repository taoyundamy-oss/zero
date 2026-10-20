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

/** Actual production projection with synthetic state; no personal memory writes or cloud calls. */
class MemoryVisualAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: VisualAcceptanceActivity

    @Test fun themesLongDetailsTailFiltersAndFailuresRemainUsable() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val report = JSONArray()
        try {
            for (width in listOf(360, 720)) for (dark in listOf(false, true))
                for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                    val name = "$width-$dark-$dynamic-$scale"
                    setPage("memory", width, dark, dynamic, scale)
                    await { it.any { node -> node.text?.toString() == "记忆管理" } }
                    assertTrue(nodes().any { it.text?.toString() == "来源未核验" })
                    val colors = requireNotNull(activity.palette)
                    for ((foreground, background) in listOf(colors.onSurface to colors.surfaceContainerLow,
                        colors.onSurfaceVariant to colors.surfaceContainerLow,
                        colors.onPrimaryContainer to colors.primaryContainer,
                        colors.onTertiaryContainer to colors.tertiaryContainer)) {
                        assertTrue("Text contrast", contrast(foreground, background) >= 4.5)
                    }
                    screenshot("$name-top")
                    val tree = await { it.any { node -> node.text?.toString()?.startsWith("长文记忆") == true } }
                    assertTrue(owner(tree.first { it.text?.toString()?.startsWith("长文记忆") == true })
                        .performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle()
                    val content = nodes().first { it.text?.toString()?.contains("MEMORY_DOCUMENT_END") == true }
                    assertNotNull(content)
                    assertTrue("Long detail scroll viewport", nodes().any { it.isScrollable })
                    reachContains("压缩层级 L")
                    screenshot("$name-detail-tail")
                    click("关闭")
                    reach("末行记忆")
                    screenshot("$name-page-tail")
                    click("返回", description = true)
                    assertEquals(listOf("back"), activity.memoryActions.toList())
                    report.put(JSONObject().put("name", name).put("longDetailTail", true)
                        .put("listTail", true).put("sourceLabel", true).put("contrast", true))
                }
            setPage("memory", 360, false, false, 1f)
            click("偏好")
            await { it.any { node -> node.text?.toString() == "末行记忆" } }
            assertFalse(nodes().any { it.text?.toString() == "记忆记录 1" })
            click("全部")
            await { it.any { node -> node.text?.toString()?.startsWith("长文记忆") == true } }
            val editor = nodes().first { it.isEditable }
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "没有此内容")
            }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)); settle()
            await { it.any { node -> node.text?.toString() == "无匹配结果" } }
            assertTrue(activity.memoryActions.any { it == "query:没有此内容" })
            for (page in listOf("memory-empty", "memory-delete-error")) {
                setPage(page, 360, true, false, 2f)
                await { it.any { node -> node.text?.toString()?.contains(
                    if (page == "memory-empty") "暂无记忆" else "删除未完成，数据已保留，请重试") == true } }
                screenshot(page)
                assertFalse(activity.memoryActions.any { it.startsWith("delete:") })
            }
            File(instrumentation.targetContext.filesDir, "a8-memory/report.json").writeText(JSONObject()
                .put("cases", report).put("filterAndSearch", true).put("emptyAndDeleteError", true)
                .put("personalDataWrites", 0).put("providerRequests", 0).put("passed", true)
                .put("scope", "production_projection_with_isolated_callbacks_not_Room_deletion").toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun reachContains(label: String) {
        repeat(40) {
            val tree = nodes()
            if (tree.any { it.isVisibleToUser && it.text?.toString()?.contains(label) == true }) return
            val scroll = tree.firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            assertNotNull("No scroll for long memory tail", scroll)
            assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); settle()
        }
        fail("Memory tail cannot reach end of viewport")
    }

    private fun setPage(page: String, width: Int, dark: Boolean, dynamic: Boolean, scale: Float) {
        instrumentation.runOnMainSync {
            activity.finish()
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        assertEquals(scale, activity.resources.configuration.fontScale, 0f)
        instrumentation.runOnMainSync {
            activity.memoryActions.clear()
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
        throw AssertionError("Memory UI condition not reached")
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
        val file = File(instrumentation.targetContext.filesDir, "a8-memory/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
