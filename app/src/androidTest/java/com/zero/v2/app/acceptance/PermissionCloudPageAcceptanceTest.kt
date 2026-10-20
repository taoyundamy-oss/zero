package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.PermissionCloudCall
import com.zero.v2.app.ui.settings.PermissionCenterScreen
import com.zero.v2.app.ui.theme.ZeroTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PermissionCloudPageAcceptanceTest {
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
    private fun find(label: String, backward: Boolean = false): AccessibilityNodeInfo {
        repeat(100) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == label }?.let { return it }
            if (attempt % 2 == 1) nodes().firstOrNull { it.isScrollable }?.performAction(
                if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(60)
        }
        error("Missing cloud UI: $label")
    }
    private fun click(label: String) {
        repeat(40) {
            var node = find(label)
            while (!node.isClickable) {
                node = requireNotNull(node.parent)
                assertTrue("Action owner must refresh: $label", node.refresh())
            }
            if (node.isEnabled) {
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(100)
                return
            }
            Thread.sleep(50)
        }
        capture("action-unavailable")
        File(inst.targetContext.filesDir, "a8-cloud-ui/action-unavailable.txt").writeText(nodes().joinToString("\n") {
            val bounds = android.graphics.Rect().also(it::getBoundsInScreen)
            "${it.text}|${it.className}|enabled=${it.isEnabled}|clickable=${it.isClickable}|visible=${it.isVisibleToUser}|$bounds"
        })
        error("Cloud action never enabled: $label")
    }
    private fun capture(name: String) {
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
        val dir = File(inst.targetContext.filesDir, "a8-cloud-ui").apply { mkdirs() }
        try { File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun config() = JSONObject().put("forward_whitelist", JSONArray().put("remote"))
        .put("daily_forward_cap", 200).put("auto_forward_enabled", true)

    @Test fun productionPageReadRetryAndInvalidInputAreVisible() {
        val available = java.util.concurrent.atomic.AtomicBoolean()
        val cloud = PermissionCloudCall { tool, args ->
            when (tool) {
                "im_config_get" -> if (available.get()) config() else null
                "im_config_set" -> JSONObject(args["updates"] as Map<*, *>)
                else -> null
            }
        }
        PermissionStatusAcceptanceTest().fixture(cloud = cloud) { vm ->
            var activity: VisualAcceptanceActivity? = null
            try {
                VisualAcceptanceActivity.fontScaleForNextLaunch = 1f
                activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                inst.runOnMainSync {
                    activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    activity!!.setContent { ZeroTheme(darkTheme = false, dynamicColor = false) { PermissionCenterScreen({}, vm) } }
                }
                find("无法读取通道参数，请重试。")
                capture("read-failed")
                available.set(true)
                click("重试读取")
                repeat(100) { if (!vm.state.value.imConfig.loaded || vm.state.value.imConfig.loading) Thread.sleep(20) }
                assertTrue("Retry must load before editing", vm.state.value.imConfig.loaded)
                assertFalse(vm.state.value.imConfig.loading)
                val cap = find("200", backward = true)
                assertTrue(cap.isEditable)
                assertTrue(cap.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "xyz")
                }))
                inst.waitForIdleSync()
                File(inst.targetContext.filesDir, "a8-cloud-ui/edit-state.txt").writeText(vm.state.value.imConfig.toString())
                click("保存参数")
                find("请输入有效的非负整数上限。")
                assertEquals("xyz", vm.state.value.imConfig.capText)
                capture("invalid-cap")
                assertTrue(inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                find("返回前确认")
                click("保留编辑")
                assertEquals("xyz", vm.state.value.imConfig.capText)
                inst.runOnMainSync { vm.editImConfig("remote", "200", true) }
                click("保存参数")
                find("参数已保存。")
                capture("save-confirmed")
            } finally { inst.runOnMainSync { activity?.finish() } }
        }
    }

    @Test fun cloudFormRealResourcesMatrix() {
        val split = InstrumentationRegistry.getArguments().getString("permissionWindow") == "split"
        val report = JSONArray()
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            PermissionStatusAcceptanceTest().fixture(cloud = PermissionCloudCall { tool, _ ->
                if (tool == "im_config_get") config() else null
            }) { vm ->
                var activity: VisualAcceptanceActivity? = null
                try {
                    repeat(100) { if (!vm.state.value.imConfig.loaded) Thread.sleep(20) }
                    assertTrue(vm.state.value.imConfig.loaded)
                    inst.runOnMainSync { vm.editImConfig("sample_contact_".repeat(8), "invalid", false); vm.saveImConfig() }
                    VisualAcceptanceActivity.fontScaleForNextLaunch = scale
                    activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                    inst.runOnMainSync {
                        activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        activity!!.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) { PermissionCenterScreen({}, vm) } }
                    }
                    if (split) SplitWindowAcceptance.ensure(activity!!)
                    val width = activity!!.resources.configuration.screenWidthDp
                    assertEquals(scale, activity!!.resources.configuration.fontScale, 0f)
                    if (split) { assertTrue(activity!!.isInMultiWindowMode); assertTrue(width in 280..599) }
                    else assertTrue(width >= 600)
                    find("云服通道参数")
                    capture("$split-$scale-$dark-$dynamic-fields")
                    find("请输入有效的非负整数上限。")
                    capture("$split-$scale-$dark-$dynamic-error")
                    find("重试读取")
                    report.put(JSONObject().put("scale", scale).put("dark", dark).put("dynamic", dynamic)
                        .put("widthDp", width).put("realSplit", activity!!.isInMultiWindowMode).put("errorAndRetryVisible", true))
                    File(inst.targetContext.filesDir, "a8-cloud-ui/report-$split.json").writeText(report.toString(2))
                } finally { inst.runOnMainSync { activity?.finish() } }
            }
        }
    }
}
