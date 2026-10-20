package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.log.RunLogContent
import com.zero.v2.app.ui.log.RunLogState
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.trace.AgentTrace
import com.zero.v2.core.trace.NodeType
import com.zero.v2.core.trace.TraceSummary
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogFullTextAcceptanceTest {
    @Test fun detailSearchMustAllowReadingTheMatchedFullText() {
        val inst = InstrumentationRegistry.getInstrumentation()
        VisualAcceptanceActivity.fontScaleForNextLaunch = 2f
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val detail = "isolated full detail marker\n完整参数与结果必须能够阅读"
        val trace = AgentTrace("isolated-log", 1, nodeType = NodeType.TOOL_CALL_RESULT, sourceModule = "isolated", summary = "isolated summary", detail = detail)
        val state = RunLogState(listOf(trace.traceId), mapOf(trace.traceId to listOf(trace)), mapOf(trace.traceId to TraceSummary(trace.traceId, 1, 0, 0, 0, 1, 1)))
        fun nodes(): List<AccessibilityNodeInfo> {
            val out = mutableListOf<AccessibilityNodeInfo>()
            fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
            visit(inst.uiAutomation.rootInActiveWindow); return out
        }
        try {
            SplitWindowAcceptance.ensure(activity)
            inst.runOnMainSync { activity.setContent { ZeroTheme { RunLogContent(state, {}, {}, {}) } } }
            var field: AccessibilityNodeInfo? = null
            repeat(30) { if (field == null) { field = nodes().firstOrNull { it.isEditable }; Thread.sleep(100) } }
            assertTrue(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "full detail marker") }))
            var found = false
            repeat(40) { attempt ->
                if (nodes().any { it.isVisibleToUser && it.text?.toString() == detail }) found = true
                if (!found) { if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(100) }
            }
            assertTrue("Matched detail must be readable on actual production page", found)
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
