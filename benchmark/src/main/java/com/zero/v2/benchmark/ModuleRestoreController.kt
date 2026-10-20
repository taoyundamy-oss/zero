package com.zero.v2.benchmark

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

/** Delivery-only normal UI operation; does not edit saved module configuration. */
@RunWith(AndroidJUnit4::class)
class ModuleRestoreController {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        for (window in automation.windows) {
            if (!window.isActive && !window.isFocused) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() == "com.zero.v2") walk(root)?.let { return it }
        }
        return null
    }

    private fun click(label: String) {
        repeat(100) {
            var node = find { it.isClickable && (it.text?.toString() == label ||
                it.contentDescription?.toString() == label || it.contentDescription?.toString()?.startsWith("$label，") == true) }
            while (node != null && !node.isClickable) node = node.parent
            if (node != null && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Thread.sleep(500)
                return
            }
            Thread.sleep(100)
        }
        error("Observed control unavailable: $label")
    }

    @Test fun restoreOriginalQqThroughUi() {
        Configurator.getInstance().waitForIdleTimeout = 0
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
            "am start -W --user 0 -n com.zero.v2/com.zero.v2.app.MainActivity")
        click("动态模块")
        repeat(12) {
            val toggle = find { it.contentDescription?.toString() == "启停 qqbot" }
            if (toggle != null) {
                check(!toggle.isChecked) { "Refuse to disable an already enabled module" }
                check(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                repeat(100) {
                    if (find { it.contentDescription?.toString() == "启停 qqbot" && it.isChecked } != null) return
                    Thread.sleep(300)
                }
                error("Normal QQ enable operation did not complete")
            }
            val list = find { it.isScrollable }
            if (list != null) check(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            Thread.sleep(300)
        }
        error("QQ enable control was not observed; no database changes performed")
    }
}
