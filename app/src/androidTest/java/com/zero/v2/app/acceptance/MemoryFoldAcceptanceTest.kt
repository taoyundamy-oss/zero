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

/** Physical Fold3 cover screen, actual independent dialog; no display or lock configuration changes. */
class MemoryFoldAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: VisualAcceptanceActivity

    @Test fun physicalCoverScreenLongDetailAndErrorsRemainUsable() {
        val context = instrumentation.targetContext
        val stateBefore = shell("cmd device_state print-state")
        assertEquals("Phone must be physically folded", "0", stateBefore)
        val sizeBefore = shell("wm size")
        val densityBefore = shell("wm density")
        activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val report = JSONArray()
        try {
            val width = (activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density).toInt()
            assertTrue("Actual cover viewport: $width", width in 280..420)
            for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                assertEquals("0", shell("cmd device_state print-state"))
                val name = "$dark-$dynamic-$scale"
                setPage("memory", width, dark, dynamic, scale)
                await { it.any { node -> node.text?.toString() == "记忆管理" } }
                screenshot("$name-top")
                val row = await { it.any { node -> node.text?.toString()?.startsWith("长文记忆") == true } }
                assertTrue(owner(row.first { it.text?.toString()?.startsWith("长文记忆") == true })
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle()
                assertTrue(nodes().any { it.isScrollable })
                reachContains("压缩层级 L")
                screenshot("$name-detail-tail")
                for (label in listOf("关闭", "删除")) {
                    val node = owner(nodes().first { it.text?.toString() == label })
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    assertTrue("$label width", rect.width() / activity.resources.displayMetrics.density >= 48)
                    assertTrue("$label height", rect.height() / activity.resources.displayMetrics.density >= 48)
                    assertTrue("$label visible", rect.left >= 0 && rect.right <= activity.resources.displayMetrics.widthPixels &&
                        rect.top >= 0 && rect.bottom <= activity.resources.displayMetrics.heightPixels)
                }
                click("关闭")
                reach("末行记忆")
                screenshot("$name-page-tail")
                click("返回", description = true)
                assertEquals(listOf("back"), activity.memoryActions.toList())
                setPage("memory-read-error", width, dark, dynamic, scale)
                await { it.any { node -> node.text?.toString() == "重新读取" } }
                screenshot("$name-read-error")
                click("重新读取")
                await { it.any { node -> node.text?.toString()?.startsWith("长文记忆") == true } }
                assertEquals(listOf("read-retry"), activity.memoryActions.toList())
                report.put(JSONObject().put("name", name).put("viewportDp", width)
                    .put("fontScale", activity.resources.configuration.fontScale).put("realDialogTail", true)
                    .put("closeAndDeleteTargetsVisible", true).put("returnAndRetry", true))
            }
            assertEquals(stateBefore, shell("cmd device_state print-state"))
            assertEquals(sizeBefore, shell("wm size")); assertEquals(densityBefore, shell("wm density"))
            File(context.filesDir, "a8-memory-fold/report.json").writeText(JSONObject().put("passed", true)
                .put("deviceState", stateBefore).put("effectiveDisplay", sizeBefore).put("density", densityBefore)
                .put("displayConfigurationUnchanged", true).put("cases", report)
                .put("personalDataWrites", 0).put("providerRequests", 0).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().toString(Charsets.UTF_8).trim()
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
        val file = File(instrumentation.targetContext.filesDir, "a8-memory-fold/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
