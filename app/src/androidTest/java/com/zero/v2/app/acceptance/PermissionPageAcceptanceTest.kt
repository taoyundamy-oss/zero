package com.zero.v2.app.acceptance

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.PermissionCenterScreen
import com.zero.v2.app.ui.settings.PermStatus
import com.zero.v2.app.ui.theme.ZeroTheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production page/isolated VM; failed launch only, no personal permission or remote config edits. */
class PermissionPageAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null || !n.refresh()) return
            all.add(n); for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow); return all
    }
    private fun find(label: String, backward: Boolean = false): AccessibilityNodeInfo {
        repeat(50) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == label }?.let { return it }
            if (attempt % 2 == 1) nodes().firstOrNull { it.isScrollable }?.performAction(
                if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(80)
        }
        error("Missing permission page text: $label")
    }
    private fun click(label: String) {
        var n = find(label)
        while (!n.isClickable) n = requireNotNull(n.parent)
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(200)
    }
    private fun shot(name: String) {
        val image = requireNotNull(inst.uiAutomation.takeScreenshot())
        val folder = File(inst.targetContext.filesDir, "a8-permissions-ui").apply { mkdirs() }
        try { File(folder, "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { image.recycle() }
    }

    @Test fun actualSystemSettingsRoundTripRefreshesStatus() {
        val denied = java.util.concurrent.atomic.AtomicBoolean(false)
        val probe = object : ContextWrapper(inst.targetContext) {
            override fun getSystemService(name: String): Any? {
                if (name == android.content.Context.ALARM_SERVICE && denied.get()) throw SecurityException("isolated changed probe")
                return super.getSystemService(name)
            }
        }
        PermissionStatusAcceptanceTest().fixture(probe) { vm ->
            org.junit.Assume.assumeTrue(vm.state.value.items.single { it.key == "exact_alarm" }.status == PermStatus.GRANTED)
            var activity: VisualAcceptanceActivity? = null
            try {
                VisualAcceptanceActivity.fontScaleForNextLaunch = 1f
                activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                inst.runOnMainSync { activity!!.setContent {
                    ZeroTheme(darkTheme = false, dynamicColor = false) { PermissionCenterScreen({}, vm) }
                } }
                var anchor = find("使用情况访问")
                fun action(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.text?.toString() == "前往设置") return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { action(it)?.let { found -> return found } }
                    return null
                }
                var button: AccessibilityNodeInfo? = null
                repeat(6) {
                    if (button == null) { button = action(anchor); anchor.parent?.let { anchor = it } }
                }
                var target = requireNotNull(button)
                while (!target.isClickable) target = requireNotNull(target.parent)
                assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                repeat(40) { if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.settings") Thread.sleep(100) }
                assertEquals("com.android.settings", inst.uiAutomation.rootInActiveWindow?.packageName?.toString())
                denied.set(true) // Only the isolated read changes; no phone permission is altered.
                assertTrue(inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                find("系统权限", backward = true)
                repeat(40) {
                    if (vm.state.value.items.single { it.key == "exact_alarm" }.status != PermStatus.DENIED) Thread.sleep(50)
                }
                assertEquals(PermStatus.DENIED, vm.state.value.items.single { it.key == "exact_alarm" }.status)
                shot("settings-return-refreshed")
            } finally { inst.runOnMainSync { activity?.finish() } }
        }
    }

    @Test fun systemDestinationFailureIsVisibleOnProductionPage() {
        PermissionStatusAcceptanceTest().fixture { vm ->
            var activity: VisualAcceptanceActivity? = null
            var attempts = 0
            try {
                VisualAcceptanceActivity.fontScaleForNextLaunch = 1f
                activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                val failed = object : ContextWrapper(activity) {
                    override fun startActivity(intent: Intent) { attempts++; throw SecurityException("isolated system page denial") }
                }
                inst.runOnMainSync { activity!!.setContent {
                    ZeroTheme(darkTheme = false, dynamicColor = false) {
                        CompositionLocalProvider(LocalContext provides failed) { PermissionCenterScreen({}, vm) }
                    }
                } }
                click("安装引导")
                assertEquals(1, attempts)
                find("无法打开此页面，请稍后重试或从系统设置中查找。")
                shot("destination-failed")
            } finally { inst.runOnMainSync { activity?.finish() } }
        }
    }

    @Test fun permissionPageRealResourcesAndThemeMatrix() {
        val report = org.json.JSONArray()
        val split = InstrumentationRegistry.getArguments().getString("permissionWindow") == "split"
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            PermissionStatusAcceptanceTest().fixture { vm ->
                var activity: VisualAcceptanceActivity? = null
                try {
                    VisualAcceptanceActivity.fontScaleForNextLaunch = scale
                    activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                    inst.runOnMainSync { activity!!.setContent {
                        ZeroTheme(darkTheme = dark, dynamicColor = dynamic) { PermissionCenterScreen({}, vm) }
                    } }
                    if (split) SplitWindowAcceptance.ensure(activity!!)
                    assertEquals(scale, activity!!.resources.configuration.fontScale, 0f)
                    val widthDp = activity!!.resources.configuration.screenWidthDp
                    if (split) {
                        assertTrue(activity!!.isInMultiWindowMode)
                        assertTrue("Real narrow window: $widthDp", widthDp in 280..599)
                    } else assertTrue("Real wide window: $widthDp", widthDp >= 600)
                    find("系统权限")
                    shot("$split-$scale-$dark-$dynamic-head")
                    find("后台唤醒锁")
                    shot("$split-$scale-$dark-$dynamic-tail")
                    val rect = Rect().also { find("安装引导", backward = true).getBoundsInScreen(it) }
                    assertTrue("Visible Boot action", rect.width() > 0 && rect.height() > 0)
                    vm.state.value.moduleDecls.lastOrNull()?.let { last ->
                        find(last.displayName)
                        find(last.permissions.joinToString(" · "))
                    }
                    shot("$split-$scale-$dark-$dynamic-end")
                    report.put(org.json.JSONObject().put("scale", scale).put("dark", dark).put("dynamic", dynamic)
                        .put("widthDp", activity!!.resources.configuration.screenWidthDp)
                        .put("realSplit", activity!!.isInMultiWindowMode).put("headTailReachable", true)
                        .put("lastDeclarationReachable", true).put("declarationCount", vm.state.value.moduleDecls.size))
                    File(inst.targetContext.filesDir, "a8-permissions-ui/report-$split.json").writeText(report.toString(2))
                } finally { inst.runOnMainSync { activity?.finish() } }
            }
        }
    }
}
