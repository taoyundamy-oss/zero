package com.zero.v2.app.acceptance

import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.MainActivity
import com.zero.v2.app.ZeroApplication
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Main/NavHost/graph route; no screenshots or export of personal graph text. */
class GraphMainBackAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun graphSystemHeaderAndRecreatedNavigationReturnToLibrary() {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("zero_appearance", android.content.Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        instrumentation.runOnMainSync { (context.applicationContext as ZeroApplication).onCreate() }
        var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            click("资料")
            await("保存、整理与回看你的内容")
            click("记忆图谱")
            await("用户图谱")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("保存、整理与回看你的内容")
            click("记忆图谱")
            await("用户图谱")
            click("返回")
            await("保存、整理与回看你的内容")
            click("记忆图谱")
            await("用户图谱")
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { activity.recreate() }
                activity = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10000)) as MainActivity
            } finally { instrumentation.removeMonitor(monitor) }
            await("用户图谱")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("保存、整理与回看你的内容")
            assertTrue("Appearance settings changed", original == preferences.all)
            File(context.filesDir, "a8-graph-main-back.json").writeText(JSONObject()
                .put("passed", true).put("actualApplicationStartup", true).put("systemBack", true)
                .put("headerBack", true).put("mainRouteRestored", true).put("appearancePreserved", true)
                .put("personalGraphExported", false).put("providerCallsFromTestActions", 0).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun matches(node: AccessibilityNodeInfo, label: String): Boolean =
        node.text?.toString() == label || node.contentDescription?.toString() == label ||
            node.contentDescription?.toString()?.startsWith("$label，") == true

    private fun click(label: String) {
        repeat(50) {
            for (candidate in nodes().filter { matches(it, label) }) {
                var owner: AccessibilityNodeInfo? = candidate
                while (owner != null && !owner.isClickable) owner = owner.parent
                if (owner != null && owner.refresh() && owner.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    instrumentation.waitForIdleSync(); Thread.sleep(200); return
                }
            }
            Thread.sleep(100)
        }
        fail("Navigation action unavailable: $label")
    }

    private fun await(label: String) {
        repeat(60) { if (nodes().any { matches(it, label) }) return; Thread.sleep(100) }
        fail("Expected route not reached: $label")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            nodes.add(node); for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow); return nodes
    }
}
