package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DynConfigField
import com.zero.v2.app.ui.settings.DynModuleDetailContent
import com.zero.v2.app.ui.settings.DynamicModulesUiState
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.data.entity.DynamicModuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Production detail/form projection; deployment slot is isolated and remains a separate gate. */
class DynamicModuleDetailVisualAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null || !n.refresh()) return
            out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow); return out
    }
    private fun find(label: String, back: Boolean = false): AccessibilityNodeInfo {
        repeat(80) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(
                if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Missing isolated detail control: $label")
    }
    private fun owner(label: String, back: Boolean = false): AccessibilityNodeInfo {
        var n = find(label, back); while (!n.isClickable) n = requireNotNull(n.parent); return n
    }
    private fun click(label: String, back: Boolean = false) {
        assertTrue(owner(label, back).performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(100)
    }
    private fun edit(label: String, value: String) {
        assertTrue(find("编辑 $label").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        Thread.sleep(100)
    }
    private fun end() {
        repeat(120) {
            val node = nodes().firstOrNull { it.isScrollable } ?: error("Missing detail scroll")
            if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) return
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); Thread.sleep(70)
        }
        error("Detail scroll end unavailable")
    }
    private fun shot(activity: VisualAcceptanceActivity, folder: String, name: String) {
        Thread.sleep(400)
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
        val file = File(inst.targetContext.filesDir, "a8-dynamic-detail/$folder/$name.png")
        file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private val module = DynamicModuleEntity("isolated_detail_long_name_for_narrow_window_and_large_font", source =
        (1..32).joinToString("\n") { "# isolated detail source line $it" } + "\n# source tail marker", createdAt = 1,
        state = "ACTIVE", lastEndpoint = "http://isolated-runtime.invalid/diagnostic-only-long-path")
    private val fields = listOf(
        DynConfigField("name", "隔离名称", "text", "original", null, null, ""),
        DynConfigField("enabled", "隔离开关", "toggle", "false", null, null, ""),
        DynConfigField("limit", "隔离数量", "number", "5", null, null, ""),
        DynConfigField("threshold", "隔离阈值", "number", "3", 0.0, 10.0, ""))
    private fun state() = DynamicModulesUiState(loading = false, detail = module, detailId = module.id,
        detailFields = fields, detailValues = fields.associate { it.key to it.default })
    @Test fun detailAllInputsFoldsAndScrollRestoreAcrossEightActualResourceConditions() {
        val report = org.json.JSONArray()
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            val state = mutableStateOf(state()); var saved: Map<String, String>? = null
            fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                val c = MaterialTheme.colorScheme
                for ((fg, bg) in listOf(c.onSurface to c.surfaceContainerLow, c.onErrorContainer to c.errorContainer)) {
                    val a = fg.luminance(); val b = bg.luminance()
                    assertTrue((maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f) >= 4.5f)
                }
                DynModuleDetailContent(module.id, state.value, {}, {},
                    { state.value = state.value.copy(actionError = null) }, { saved = it }, { Text("隔离部署观察") })
            } } } }
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale, activity.resources.configuration.fontScale, 0f)
                val density = activity.resources.displayMetrics.density
                val width = activity.resources.displayMetrics.widthPixels / density
                assertTrue(activity.isInMultiWindowMode && width in 280f..599f)
                mount(); find(module.id); click("查看运行信息"); find("收起运行信息")
                val folder = "$scale-$dark-$dynamic"; shot(activity, folder, "detail-head")
                edit("隔离名称", "isolated retained draft"); click("切换 隔离开关")
                edit("隔离数量", "-2.5")
                assertTrue(find("调整 隔离阈值").performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 7.25f) }))
                repeat(30) { if (find("编辑 隔离阈值").text.toString() != "7.25") Thread.sleep(100) }
                assertEquals("7.25", find("编辑 隔离阈值").text.toString())
                val button = owner("保存配置"); val rect = Rect().also(button::getBoundsInScreen)
                assertTrue(rect.width() / density >= 48 && rect.height() / density >= 48)
                assertTrue(SplitWindowAcceptance.bounds(activity).contains(rect))
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(100)
                val expected = mapOf("name" to "isolated retained draft", "enabled" to "true", "limit" to "-2.5", "threshold" to "7.25")
                assertEquals(expected, saved); shot(activity, folder, "config-controls")
                click("查看源码"); end(); shot(activity, folder, "source-tail")
                activity = SplitWindowAcceptance.recreate(activity, scale); mount()
                find(module.source)
                assertTrue("Outer source scroll must restore at end", nodes().first { it.isScrollable }.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                shot(activity, folder, "source-restored")
                find("收起源码", back = true)
                assertEquals("isolated retained draft", find("编辑 隔离名称", back = true).text.toString())
                assertTrue(find("切换 隔离开关").isChecked)
                assertEquals("-2.5", find("编辑 隔离数量").text.toString())
                assertEquals("7.25", find("编辑 隔离阈值").text.toString())
                find("收起运行信息", back = true)
                report.put(org.json.JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic)
                    .put("widthDp", width).put("actualMultiWindow", true).put("fourControlsAndFoldsRestored", true))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir, "a8-dynamic-detail/report.json").writeText(org.json.JSONObject()
            .put("passed", true).put("conditions", report).put("scope", "production detail/form; deployment observation isolated")
            .put("providerRequests", 0).put("personalDataWrites", 0).toString(2))
    }
    @Test fun actualKeyboardLeavesSaveActionReachableAtTwoHundredPercent() {
        VisualAcceptanceActivity.fontScaleForNextLaunch = 2f
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        var submitted: Map<String, String>? = null
        try {
            SplitWindowAcceptance.ensure(activity)
            assertEquals(2f, activity.resources.configuration.fontScale, 0f)
            inst.runOnMainSync { activity.setContent { ZeroTheme { DynModuleDetailContent(module.id, state(), {}, {}, {},
                { submitted = it }, { Text("隔离部署观察") }) } } }
            val field = find("编辑 隔离名称")
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            repeat(40) {
                if (inst.uiAutomation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) Thread.sleep(100)
            }
            val ime = inst.uiAutomation.windows.first { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            assertTrue("Only inject keys into the focused fixture", activity.hasWindowFocus())
            val now = android.os.SystemClock.uptimeMillis()
            inst.sendKeySync(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_A, 0, android.view.KeyEvent.META_CTRL_ON))
            inst.sendKeySync(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP,
                android.view.KeyEvent.KEYCODE_A, 0, android.view.KeyEvent.META_CTRL_ON))
            inst.sendStringSync("isolated keyboard draft")
            find("isolated keyboard draft")
            val button = owner("保存配置"); val rect = Rect().also(button::getBoundsInScreen)
            assertFalse("Save must be above actual keyboard", Rect.intersects(rect, Rect().also(ime::getBoundsInScreen)))
            assertTrue(SplitWindowAcceptance.bounds(activity).contains(rect))
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(100)
            assertEquals("isolated keyboard draft", submitted?.get("name"))
            shot(activity, "ime", "save-above-keyboard")
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
