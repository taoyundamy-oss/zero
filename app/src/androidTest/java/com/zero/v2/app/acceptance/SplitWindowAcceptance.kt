package com.zero.v2.app.acceptance

import android.app.Activity
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Restore fixture and calculator into the device's existing real split stages after instrumentation stops the app. */
internal object SplitWindowAcceptance {
    fun launchIntent(context: android.content.Context): android.content.Intent =
        android.content.Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                if (InstrumentationRegistry.getArguments().getString("retainSplitTask") == "true") {
                    action = "com.zero.v2.acceptance.FIXTURE_${System.nanoTime()}"
                }
            }

    private var anchorCreated = false

    private fun retainTaskRoot(activity: Activity) {
        val inst = InstrumentationRegistry.getInstrumentation()
        if (anchorCreated || InstrumentationRegistry.getArguments().getString("retainSplitTask") != "true") return
        val monitor = inst.addMonitor(WindowAcceptanceAnchorActivity::class.java.name, null, false)
        try {
            inst.runOnMainSync { activity.startActivity(android.content.Intent(activity, WindowAcceptanceAnchorActivity::class.java)) }
            val anchor = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10_000))
            assertTrue("Anchor must belong to the tested task", anchor.taskId == activity.taskId)
            inst.runOnMainSync {
                anchor.startActivity(android.content.Intent(anchor, activity.javaClass)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            anchorCreated = true
        } finally {
            inst.removeMonitor(monitor)
        }
    }
    fun bounds(activity: Activity): android.graphics.Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        repeat(50) {
            val window = automation.windows.firstOrNull { it.root?.packageName?.toString() == activity.packageName }
            if (window != null) {
                val rect = android.graphics.Rect().also(window::getBoundsInScreen)
                if (rect.left >= 0 && rect.top >= 0 && rect.width() > 0 && rect.height() > 0) return rect
            }
            Thread.sleep(100)
        }
        val windows = automation.windows.map { w ->
            val r = android.graphics.Rect().also(w::getBoundsInScreen)
            "${w.id}:${w.type}:${w.root?.packageName}:$r"
        }
        error("Visible fixture accessibility window frame unavailable: $windows")
    }
    private fun focus(activity: Activity) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bounds = bounds(activity)
        val x = bounds.right - 8 * activity.resources.displayMetrics.density
        val y = bounds.top + 80 * activity.resources.displayMetrics.density
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,x,y,0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event,true)) } finally { event.recycle() }
        }
        repeat(50) { if (automation.rootInActiveWindow?.packageName?.toString() == "com.zero.v2") return; Thread.sleep(100) }
        error("Fixture window must own accessibility focus")
    }
    fun recreate(activity: VisualAcceptanceActivity, scale: Float): VisualAcceptanceActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(VisualAcceptanceActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync {
                VisualAcceptanceActivity.fontScaleForNextLaunch = scale
                activity.recreate()
            }
            val restored = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10000)) as VisualAcceptanceActivity
            Thread.sleep(100);focus(restored)
            return restored
        } finally { instrumentation.removeMonitor(monitor) }
    }
    fun ensure(activity: Activity) {
        retainTaskRoot(activity)
        if (InstrumentationRegistry.getArguments().getString("awaitManualSplit") == "true") {
            val deadline = android.os.SystemClock.uptimeMillis() + 300_000L
            while (!activity.isInMultiWindowMode && android.os.SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(250)
            }
            assertTrue("Actual split window must be provided before running assertions", activity.isInMultiWindowMode)
        }
        if (activity.isInMultiWindowMode) { focus(activity);return }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null || !node.refresh()) return
                result.add(node); for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(automation.rootInActiveWindow); return result
        }
        fun find(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            repeat(50) { nodes().firstOrNull(predicate)?.let { return it }; Thread.sleep(100) }
            error("System split action unavailable: $label")
        }
        fun click(node: AccessibilityNodeInfo) {
            var owner = node
            while (!owner.isClickable) owner = requireNotNull(owner.parent)
            assertTrue(owner.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(500)
        }
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS))
        click(find("Zero app icon") { node ->
            node.packageName?.toString() == "com.sec.android.app.launcher" &&
                node.contentDescription?.toString()?.contains("Zero") == true &&
                (node.viewIdResourceName?.contains("icon") == true || node.className?.toString() == "android.widget.ImageView")
        })
        click(find("Open in split view") { it.text?.toString()?.contains("分屏") == true })
        val calculatorLabels = setOf("计算器", "計算器", "計算機", "Calculator")
        click(find("Calculator peer") { it.text?.toString() in calculatorLabels || it.contentDescription?.toString() in calculatorLabels })
        repeat(50) { if (activity.isInMultiWindowMode) { focus(activity);return }; Thread.sleep(100) }
        assertTrue("Fixture must enter actual multi-window", activity.isInMultiWindowMode)
    }
}
