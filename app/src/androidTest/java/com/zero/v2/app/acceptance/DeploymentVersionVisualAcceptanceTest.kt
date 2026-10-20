package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DeploymentStatus
import com.zero.v2.app.ui.settings.DeploymentVersionContent
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.moduleapi.ReleaseVersionStatus
import com.zero.v2.core.moduleapi.StatusFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Actual production card with synthetic journal states, resources/split and real retry callback. */
class DeploymentVersionVisualAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
        visit(inst.uiAutomation.rootInActiveWindow); return out
    }
    private fun find(label: String, back: Boolean = false): AccessibilityNodeInfo {
        repeat(60) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == label }?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(
                if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Missing isolated deployment card field: $label")
    }
    private fun shot(activity: VisualAcceptanceActivity, folder: String, name: String) {
        Thread.sleep(400)
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
        val f = File(inst.targetContext.filesDir, "a8-deployment-ui/$folder/$name.png")
        f.parentFile!!.mkdirs(); f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun sixJournalStatesRemainReadableInAllEightConditions() {
        val active = ReleaseVersionStatus("current", "a0c0ffee".repeat(8), "ACTIVE", 1)
        val old = ReleaseVersionStatus("old", "e1c0ffee".repeat(8), "ISOLATED_UNKNOWN_LONG_RELEASE_STATE", 1)
        val failed = ReleaseVersionStatus("failed", "fa11eded".repeat(8), "FAILED", 1)
        val live = DeploymentStatus(loaded = true, freshness = StatusFact.LIVE, active = active, history = listOf(active, old, failed))
        val cases = listOf(
            Triple("live", live, "当前活动版本"),
            Triple("stale", live.copy(freshness = StatusFact.STALE, error = "部署观测已过期，等待后台更新"), "观测已过期"),
            Triple("unknown", live.copy(freshness = StatusFact.UNKNOWN, error = "部署状态未知，等待运行端回报"), "当前部署状态尚未确认"),
            Triple("loading", DeploymentStatus(), "正在读取部署状态…"),
            Triple("empty", DeploymentStatus(loaded = true, freshness = StatusFact.LIVE), "暂无受管活动版本"),
            Triple("error", DeploymentStatus(loaded = true, error = "部署记录读取失败，请重试"), "部署记录读取失败，请重试"))
        val reports = org.json.JSONArray()
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            val state = mutableStateOf(live); var retries = 0
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale, activity.resources.configuration.fontScale, 0f)
                val density = activity.resources.displayMetrics.density
                val width = activity.resources.displayMetrics.widthPixels / density
                assertTrue(activity.isInMultiWindowMode && width in 280f..599f)
                inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                    val c = MaterialTheme.colorScheme
                    for ((fg, bg) in listOf(c.onSurface to c.surfaceContainerLow, c.onSurfaceVariant to c.surfaceVariant,
                        c.onPrimaryContainer to c.primaryContainer, c.onTertiaryContainer to c.tertiaryContainer, c.onErrorContainer to c.errorContainer)) {
                        val a = fg.luminance(); val b = bg.luminance(); assertTrue((maxOf(a,b)+0.05f)/(minOf(a,b)+0.05f) >= 4.5f)
                    }
                    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        DeploymentVersionContent(state.value) { retries++ }
                    }
                } } }
                val folder = "$scale-$dark-$dynamic"
                for ((name, value, label) in cases) {
                    inst.runOnMainSync { state.value = value }; Thread.sleep(100)
                    find(label, back = true)
                    var button = find("重新读取状态", back = true); while (!button.isClickable) button = requireNotNull(button.parent)
                    val rect = Rect().also(button::getBoundsInScreen)
                    assertTrue(rect.width()/density >= 48 && rect.height()/density >= 48)
                    assertTrue(SplitWindowAcceptance.bounds(activity).contains(rect))
                    assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    shot(activity, folder, name)
                    if (name == "live") {
                        val hash = find("e1c0ffee"); val r = Rect().also(hash::getBoundsInScreen)
                        assertTrue(hash.isVisibleToUser && r.width() > 0 && r.height() > 0)
                        find("状态未识别：ISOLATED_UNKNOWN_LONG_RELEASE_STATE")
                        find("fa11eded"); find("部署失败"); shot(activity, folder, "history-tail")
                    }
                    if (name == "stale" || name == "unknown") find("上次观测版本", back = true)
                }
                assertEquals(cases.size, retries)
                reports.put(org.json.JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic)
                    .put("widthDp", width).put("actualMultiWindow", true).put("states", org.json.JSONArray(cases.map { it.first }))
                    .put("retryCount", retries).put("longHistoryHashReadable", true))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir, "a8-deployment-ui/report.json").writeText(org.json.JSONObject()
            .put("passed", true).put("conditions", reports).put("scope", "production deployment card; synthetic journal and retry")
            .put("providerRequests", 0).put("personalDataWrites", 0).toString(2))
    }
}
