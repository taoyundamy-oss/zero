package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.navigation.AppDestinations
import com.zero.v2.app.ui.settings.PermissionCenterScreen
import com.zero.v2.app.ui.settings.PermissionCloudCall
import com.zero.v2.app.ui.settings.PermStatus
import com.zero.v2.app.ui.settings.SettingsScreen
import com.zero.v2.app.ui.theme.ZeroTheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Fresh isolated permission reads with production settings/page; personal OS grants are unchanged. */
class PermissionEntryAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            result.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(70) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text ||
                it.contentDescription?.toString()?.startsWith("$text，") == true) }?.let { return it }
            if (attempt % 2 == 1) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(70)
        }
        error("Missing entry text: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var owner = node
        while (!owner.isClickable) {
            owner = requireNotNull(owner.parent)
            assertTrue(owner.refresh())
        }
        assertTrue(owner.isEnabled)
        assertTrue(owner.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun capture(name: String) {
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
        val folder = File(inst.targetContext.filesDir, "a8-permission-entry").apply { mkdirs() }
        try { File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
    @Test fun freshDeniedStateUsesUniqueSettingsOwnerAndCanReturn() {
        assertEquals(1, AppDestinations.settings.count { it.route == "permission_center" })
        val probe = object : ContextWrapper(inst.targetContext) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == android.Manifest.permission.RECORD_AUDIO) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE) throw SecurityException("isolated initial denial")
                return super.getSystemService(name)
            }
        }
        PermissionStatusAcceptanceTest().fixture(probe, cloud = PermissionCloudCall { _, _ -> null }) { vm ->
            var activity: VisualAcceptanceActivity? = null
            try {
                VisualAcceptanceActivity.fontScaleForNextLaunch = 1f
                activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                inst.runOnMainSync {
                    activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity!!.setContent { ZeroTheme(darkTheme = false, dynamicColor = false) {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = "settings") {
                            composable("settings") { SettingsScreen { nav.navigate(it) } }
                            composable("permission_center") { PermissionCenterScreen({ nav.popBackStack() }, vm) }
                        }
                    } }
                }
                if (activity!!.isInMultiWindowMode) SplitWindowAcceptance.ensure(activity!!)
                inst.waitForIdleSync()
                capture("entry-window")
                File(inst.targetContext.filesDir, "a8-permission-entry/window.txt").writeText(
                    "width=${activity!!.resources.configuration.screenWidthDp};split=${activity!!.isInMultiWindowMode};root=${inst.uiAutomation.rootInActiveWindow?.packageName}")
                click(find("系统权限"))
                find("精确闹钟（定时触发）")
                assertEquals(PermStatus.DENIED, vm.state.value.items.single { it.key == "exact_alarm" }.status)
                find("麦克风")
                assertEquals(PermStatus.DENIED, vm.state.value.items.single { it.key == "microphone" }.status)
                find("未授权")
                capture("initial-denied")
                assertTrue(inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                find("能力与模型")
                capture("returned-settings")
                click(find("系统权限"))
                find("麦克风")
                assertEquals(PermStatus.DENIED, vm.state.value.items.single { it.key == "microphone" }.status)
                assertFalse(vm.state.value.imConfig.dirty)
                capture("reentered-denied")
            } finally { inst.runOnMainSync { activity?.finish() } }
        }
    }
}
