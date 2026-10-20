package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.log.RunLogContent
import com.zero.v2.app.ui.log.RunLogState
import com.zero.v2.app.ui.log.formatTrace
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.trace.AgentTrace
import com.zero.v2.core.trace.NodeType
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production projection; isolated records/copy destination, real resources/window/recreation. */
class RunLogVisualAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
        visit(inst.uiAutomation.rootInActiveWindow); return out
    }
    private fun find(label: String, back: Boolean = false): AccessibilityNodeInfo {
        repeat(100) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(80)
        }
        error("Missing isolated run log field: $label")
    }
    private fun click(label: String, activity: VisualAcceptanceActivity, back: Boolean = false) {
        var n = find(label, back)
        while (!n.isClickable) n = requireNotNull(n.parent)
        val rect = Rect().also(n::getBoundsInScreen); val density = activity.resources.displayMetrics.density
        assertTrue(rect.width()/density >= 48 && rect.height()/density >= 48)
        assertTrue(SplitWindowAcceptance.bounds(activity).contains(rect))
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200)
    }
    private fun query(value: String) {
        find("搜索运行记录", true)
        val field = nodes().first { it.isEditable && it.isVisibleToUser }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        Thread.sleep(200)
    }
    private fun shot(activity: VisualAcceptanceActivity, folder: String, name: String) {
        Thread.sleep(300)
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
        val file = File(inst.targetContext.filesDir, "a8-log-ui/$folder/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun readingSearchCopyRecoveryAndFeedbackAcrossEightConditions() {
        val ids = (1..16).map { "isolated_trace_${it}_long_identifier_for_narrow_window" }
        val events = ids.mapIndexed { index, id -> id to listOf(AgentTrace(id, 1, nodeType = if (index == 15) NodeType.ERROR else NodeType.TOOL_CALL_RESULT,
            sourceModule = "isolated", summary = "isolated summary ${index+1}", detail = "a8_detail_marker_${index+1}\n完整参数与结果 ${index+1}",
            callId = "isolated_call_${index+1}_long_tool_identifier", functionName = "isolated_tool")) }.toMap()
        // No summary records: detailed reading must not depend on optional aggregate metadata.
        val normal = RunLogState(ids, events, historyIds = ids.toSet())
        val last = ids.last(); val detail = events.getValue(last).single().detail!!
        val report = org.json.JSONArray()
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            val current = mutableStateOf(normal); val copied = mutableListOf<String>(); var reloads = 0
            fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                val c = MaterialTheme.colorScheme
                for ((fg, bg) in listOf(c.onSurface to c.surfaceContainerLow, c.onSurfaceVariant to c.surfaceContainerLow, c.error to c.surfaceContainerLow)) {
                    val a = fg.luminance(); val b = bg.luminance(); assertTrue((maxOf(a,b)+0.05f)/(minOf(a,b)+0.05f) >= 4.5f)
                }
                RunLogContent(current.value, {}, { reloads++ }, { copied.add(it) })
            } } } }
            val folder = "$scale-$dark-$dynamic"
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale, activity.resources.configuration.fontScale, 0f)
                val width = activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density
                assertTrue(activity.isInMultiWindowMode && width in 280f..599f)
                mount(); find("运行记录"); shot(activity, folder, "head")
                click("复制全部记录", activity)
                assertEquals(ids.joinToString("\n\n") { formatTrace(normal, it) }, copied.single())
                click("展开记录 $last", activity); find(detail); shot(activity, folder, "expanded")
                activity = SplitWindowAcceptance.recreate(activity, scale); mount()
                assertTrue("Expanded detail/list position must restore without scrolling", nodes().any { it.isVisibleToUser && it.text?.toString() == detail })
                shot(activity, folder, "expanded-restored")
                click("复制记录 $last", activity, true); assertEquals(formatTrace(normal, last), copied.last())
                query("a8_detail_marker_16"); find(detail)
                activity = SplitWindowAcceptance.recreate(activity, scale); mount(); find(detail)
                find("搜索运行记录", true)
                assertTrue("Query must restore", nodes().any { it.isEditable && it.text?.toString() == "a8_detail_marker_16" })
                find(detail); shot(activity, folder, "search-restored")
                query("isolated_no_match"); find("没有匹配的运行记录"); shot(activity, folder, "unmatched")
                query("")
                inst.runOnMainSync { current.value = RunLogState(loading = true) }
                find("正在读取运行记录…"); shot(activity, folder, "loading")
                inst.runOnMainSync { current.value = RunLogState() }
                find("暂无运行记录"); shot(activity, folder, "empty")
                inst.runOnMainSync { current.value = normal.copy(historyError = "isolated history failure") }
                find("isolated history failure"); find(ids.first()); shot(activity, folder, "error-retained")
                click("重新读取", activity, true); assertEquals(1, reloads)
                inst.runOnMainSync { current.value = RunLogState(historyError = "isolated initial failure") }
                find("isolated initial failure"); shot(activity, folder, "error-empty")
                assertFalse(nodes().any { it.text?.toString() == "暂无运行记录" })
                report.put(org.json.JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic).put("widthDp", width)
                    .put("actualMultiWindow", true).put("fullTextAndCopy", true).put("queryExpansionAndScrollRestored", true).put("retryCount", reloads))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir, "a8-log-ui/report.json").writeText(org.json.JSONObject().put("passed", true).put("conditions", report)
            .put("scope", "production run log projection; synthetic records and copy destination").put("providerRequests", 0).put("personalDataWrites", 0).toString(2))
    }
}
