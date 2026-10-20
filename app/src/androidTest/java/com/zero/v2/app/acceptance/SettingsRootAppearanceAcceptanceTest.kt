package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.navigation.AppDestinations
import com.zero.v2.app.ui.settings.*
import com.zero.v2.app.ui.theme.ZeroTheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real settings renderer and actual appearance store/VM, with isolated preferences and system-action callback. */
class SettingsRootAppearanceAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
        visit(inst.uiAutomation.rootInActiveWindow); return out
    }
    private fun find(label: String, back: Boolean = false): AccessibilityNodeInfo {
        repeat(90) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()?.contains(label) == true || it.contentDescription?.toString() == label) }?.let { return it }
            if (attempt%3==2) nodes().firstOrNull { it.isScrollable }?.performAction(if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(80)
        }
        error("Missing isolated settings field: $label")
    }
    private fun click(label: String, activity: VisualAcceptanceActivity) {
        var n = find(label)
        while (!n.isClickable) n = requireNotNull(n.parent)
        val rect = Rect().also(n::getBoundsInScreen); val d = activity.resources.displayMetrics.density
        assertTrue(rect.width()/d >= 48 && rect.height()/d >= 48); assertTrue(SplitWindowAcceptance.bounds(activity).contains(rect))
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200)
    }
    private fun shot(activity: VisualAcceptanceActivity, folder: String, name: String) {
        Thread.sleep(300)
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
        val f = File(inst.targetContext.filesDir, "a8-settings-root/$folder/$name.png"); f.parentFile!!.mkdirs()
        f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun end() {
        repeat(60) {
            val viewport = nodes().firstOrNull { it.isScrollable } ?: error("Missing settings scroll viewport")
            if (viewport.actionList.none { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) return
            assertTrue(viewport.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); Thread.sleep(100)
        }
        error("Settings tail must be reachable")
    }
    @Test fun rootEntriesTailAndActualAppearancePersistenceInEightConditions() {
        val report = org.json.JSONArray(); val phoneContext = inst.targetContext
        assertEquals(10, AppDestinations.settings.size)
        assertEquals(10, AppDestinations.settings.map { it.route }.distinct().size)
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            val folder = "$scale-$dark-$dynamic"; val preferenceName = "a8_isolated_appearance_$folder"
            phoneContext.deleteSharedPreferences(preferenceName)
            val isolated = object : ContextWrapper(phoneContext) {
                override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(if (name == "zero_appearance") preferenceName else name, mode)
            }
            val store = AppearanceStore(isolated); val owner = ViewModelStore(); lateinit var vm: AppearanceViewModel
            inst.runOnMainSync { vm = AppearanceViewModel(store); owner.put("appearance", vm) }
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            var activity = inst.startActivitySync(SplitWindowAcceptance.launchIntent(phoneContext)) as VisualAcceptanceActivity
            val navigations = mutableListOf<String>(); var roleActions = 0; var backs = 0
            fun mountRoot() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                val c = MaterialTheme.colorScheme
                for ((fg,bg) in listOf(c.onSurface to c.surfaceContainer, c.onSurfaceVariant to c.surfaceContainer)) {
                    val a=fg.luminance();val b=bg.luminance();assertTrue((maxOf(a,b)+0.05f)/(minOf(a,b)+0.05f)>=4.5f)
                }
                SettingsContent({ navigations.add(it) }, { roleActions++ })
            } } } }
            fun mountAppearance() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                AppearanceScreen({ backs++ }, vm)
            } } } }
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale, activity.resources.configuration.fontScale, 0f)
                val width = activity.resources.displayMetrics.widthPixels/activity.resources.displayMetrics.density
                assertTrue(activity.isInMultiWindowMode && width in 280f..599f)
                mountRoot(); find("设置"); shot(activity, folder, "root-head")
                for (entry in AppDestinations.settings) click("${entry.title}，${entry.description}", activity)
                assertEquals(AppDestinations.settings.filter { it.route != "assistant_role" }.map { it.route }, navigations)
                assertEquals(1, roleActions)
                find("对话与设置保存在本机。"); end(); shot(activity, folder, "root-tail")
                activity = SplitWindowAcceptance.recreate(activity, scale); mountRoot()
                var tailRestored = false
                repeat(30) { if (nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith("对话与设置保存在本机。") == true }) tailRestored = true else Thread.sleep(100) }
                assertTrue("Settings tail scroll must restore", tailRestored)
                assertTrue("Restored tail must remain at actual list end", nodes().first { it.isScrollable }.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                shot(activity, folder, "root-restored")
                mountAppearance(); find("明暗模式"); shot(activity, folder, "appearance-head")
                for (mode in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK, AppearanceMode.SYSTEM)) {
                    click(mode.label, activity); assertEquals(mode, vm.state.value.mode)
                }
                click("系统动态色", activity); assertFalse(vm.state.value.dynamicColor)
                assertEquals(AppearanceState(AppearanceMode.SYSTEM, false), AppearanceStore(isolated).state.value)
                find("字体大小跟随系统设置"); shot(activity, folder, "appearance-tail")
                activity = SplitWindowAcceptance.recreate(activity, scale); mountAppearance()
                find("字体大小跟随系统设置"); assertEquals(AppearanceState(AppearanceMode.SYSTEM, false), vm.state.value)
                shot(activity, folder, "appearance-restored")
                // The page is connected to a real store and VM; normal user preferences remain untouched.
                var back = find("返回", true)
                while (!back.isClickable) back = requireNotNull(back.parent)
                assertTrue(back.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200); assertEquals(1, backs)
                report.put(org.json.JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic).put("widthDp", width)
                    .put("actualMultiWindow", true).put("navigationCount", navigations.size).put("systemActionCount", roleActions)
                    .put("tailRestored", true).put("actualIsolatedAppearancePersistence", true))
            } finally { inst.runOnMainSync { activity.finish(); owner.clear() }; phoneContext.deleteSharedPreferences(preferenceName) }
        }
        File(phoneContext.filesDir, "a8-settings-root/report.json").writeText(org.json.JSONObject().put("passed", true).put("conditions", report)
            .put("scope", "production root/appearance; route and system-action callbacks; actual isolated appearance store")
            .put("providerRequests", 0).put("personalDataWrites", 0).toString(2))
    }
}
