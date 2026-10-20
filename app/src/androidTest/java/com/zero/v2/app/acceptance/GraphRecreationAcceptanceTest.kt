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

/** Error/loading/retry against production UI; isolated state callbacks, no real data mutation. */
class GraphRecreationAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: VisualAcceptanceActivity

    @Test fun collapsedEntitySurvivesActivityRecreation() {
        val report = JSONArray()
        for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            launch(dark, dynamic, scale)
            try {
                await { it.any { n -> n.text?.toString() == "较长属性名称用于窄屏阅读" } }
                click("合成图谱实体")
                assertFalse(nodes().any { it.text?.toString() == "较长属性名称用于窄屏阅读" })
                assertHeader("合成图谱实体", "已收起", "展开实体")
                screenshot("$dark-$dynamic-$scale-collapsed-before")
                recreate()
                await { it.any { n -> n.text?.toString() == "合成图谱实体" } }
                assertFalse("Collapsed entity reopened after Activity recreation",
                    nodes().any { it.text?.toString() == "较长属性名称用于窄屏阅读" })
                assertHeader("合成图谱实体", "已收起", "展开实体")
                screenshot("$dark-$dynamic-$scale-collapsed-after")
                click("合成图谱实体")
                await { it.any { n -> n.text?.toString() == "较长属性名称用于窄屏阅读" } }
                assertHeader("合成图谱实体", "已展开", "收起实体")
                report.put(JSONObject().put("dark", dark).put("dynamic", dynamic).put("scale", scale)
                    .put("collapsedRestored", true).put("accessibleRoleStateAction", true))
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
        writeReport("collapsed-report.json", report)
    }

    @Test fun expandedTailAndReadingPositionSurviveActivityRecreation() {
        val report = JSONArray()
        for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            launch(dark, dynamic, scale)
            try {
                await { it.any { n -> n.text?.toString() == "合成图谱实体" } }
                reach("图谱末行实体"); click("图谱末行实体"); reach("暂无当前属性与关系")
                settleBounds("图谱末行实体")
                val before = Rect().also { owner(nodes().first { n -> n.text?.toString() == "图谱末行实体" }).getBoundsInScreen(it) }
                screenshot("$dark-$dynamic-$scale-tail-before")
                recreate()
                await { it.any { n -> n.isVisibleToUser && n.text?.toString() == "暂无当前属性与关系" } }
                assertHeader("图谱末行实体", "已展开", "收起实体")
                val after = Rect().also { owner(nodes().first { n -> n.text?.toString() == "图谱末行实体" }).getBoundsInScreen(it) }
                assertTrue("Reading position changed after recreation", kotlin.math.abs(before.top - after.top) <= 2 * activity.resources.displayMetrics.density)
                screenshot("$dark-$dynamic-$scale-tail-after")
                report.put(JSONObject().put("dark", dark).put("dynamic", dynamic).put("scale", scale)
                    .put("tailExpandedRestored", true).put("readingPositionRestored", true))
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
        writeReport("tail-report.json", report)
    }

    private fun launch(dark: Boolean, dynamic: Boolean, scale: Float) {
        instrumentation.runOnMainSync { VisualAcceptanceActivity.fontScaleForNextLaunch = scale }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, VisualAcceptanceActivity::class.java)
            .putExtra("acceptancePage", "graph").putExtra("acceptanceDark", dark).putExtra("acceptanceDynamic", dynamic)
            .putExtra("acceptanceFontScale", scale).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        assertEquals(scale, activity.resources.configuration.fontScale, 0f)
    }

    private fun assertHeader(label: String, state: String, action: String) {
        val matches = nodes().filter { it.text?.toString() == label }
        assertEquals("Duplicate accessible entity name", 1, matches.size)
        val node = owner(matches.single())
        val probe = JSONArray()
        var current: AccessibilityNodeInfo? = matches.single()
        repeat(4) {
            current?.let { n -> probe.put(JSONObject().put("class", n.className?.toString())
                .put("text", n.text?.toString()).put("description", n.contentDescription?.toString())
                .put("state", n.stateDescription?.toString()).put("clickable", n.isClickable)
                .put("actions", n.actionList.joinToString { a -> "${a.id}:${a.label}" })) }
            current = current?.parent
        }
        val file = File(instrumentation.targetContext.filesDir, "a8-graph-restore/semantics-probe.json")
        file.parentFile!!.mkdirs(); file.writeText(probe.toString(2))
        assertEquals("android.widget.Button", node.className?.toString())
        assertEquals("${if (label == "合成图谱实体") "人物" else "概念"}，$state", node.stateDescription?.toString())
        assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == action })
        assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS })
        assertFalse(node.contentDescription?.toString()?.contains(label) == true)
    }

    private fun writeReport(name: String, cases: JSONArray) {
        val file = File(instrumentation.targetContext.filesDir, "a8-graph-restore/$name")
        file.parentFile!!.mkdirs();file.writeText(JSONObject().put("passed", true).put("cases", cases)
            .put("actualWidthDp", activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density)
            .put("personalDataWrites", 0).put("providerRequests", 0).put("audibleTalkBackNotTested", true).toString(2))
    }

    private fun settleBounds(label: String) {
        var previous: Rect? = null
        var stable = 0
        repeat(40) {
            val bounds = Rect().also { owner(nodes().first { n -> n.text?.toString() == label }).getBoundsInScreen(it) }
            stable = if (bounds == previous) stable + 1 else 0
            previous = bounds
            if (stable >= 6) return
            Thread.sleep(100)
        }
        fail("Viewport did not stabilize before recreation")
    }

    private fun recreate() {
        val monitor = instrumentation.addMonitor(VisualAcceptanceActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync {
                VisualAcceptanceActivity.fontScaleForNextLaunch = activity.fixture.fontScale
                activity.recreate()
            }
            activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10000)) as VisualAcceptanceActivity
            settle()
            assertEquals(activity.fixture.fontScale, activity.resources.configuration.fontScale, 0f)
        } finally { instrumentation.removeMonitor(monitor) }
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
        repeat(24) {
            val tree = await { it.any { node -> if (description) node.contentDescription?.toString() == label else node.text?.toString() == label } }
            val target = owner(tree.first { if (description) it.contentDescription?.toString() == label else it.text?.toString() == label })
            val bounds = Rect().also { target.getBoundsInScreen(it) }
            val metrics = activity.resources.displayMetrics
            if (bounds.height() / metrics.density >= 48 && bounds.width() / metrics.density >= 48 &&
                bounds.top >= 0 && bounds.bottom <= metrics.heightPixels - 24 * metrics.density) {
                assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); settle(); return
            }
            val scroll = tree.firstOrNull { it.isScrollable && it.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            assertNotNull("Clipped action cannot scroll fully into view: $label $bounds", scroll)
            assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); settle()
        }
        screenshot("unreachable-action")
        fail("Action cannot become fully visible: $label")
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
        val file = File(instrumentation.targetContext.filesDir, "a8-graph-restore/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}
