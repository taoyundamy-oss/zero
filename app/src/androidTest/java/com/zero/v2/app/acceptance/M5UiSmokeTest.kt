package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.MemoryManageContent
import com.zero.v2.app.ui.settings.MemoryUiState
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.TaskMemory
import com.zero.v2.core.data.entity.MemoryEntity
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone rendering of production components, synthetic state, no normal startup or DB writes. */
class M5UiSmokeTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser) add(n)
            repeat(n.childCount) { visit(n.getChild(it)) }
        }
        visit(inst.uiAutomation.rootInActiveWindow)
    }
    private fun awaitText(text: String) {
        repeat(50) { if (nodes().any { it.text?.contains(text) == true }) return; Thread.sleep(100) }
        error("missing_synthetic_ui_$text")
    }
    private fun screenshot(activity: VisualAcceptanceActivity, name: String) {
        val b = inst.uiAutomation.takeScreenshot(activity.window); assertNotNull(b)
        val file = File(inst.targetContext.filesDir, "m5-ui-20261018/$name.png")
        file.parentFile!!.mkdirs(); file.outputStream().use { b!!.compress(Bitmap.CompressFormat.PNG, 100, it) }; b!!.recycle()
    }
    @Test fun sixMemoryKindsAndUsageChartRenderWithoutPersonalWrites() {
        var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            val types = listOf(MemoryType.FACT, MemoryType.EVENT, MemoryType.PREFERENCE,
                MemoryType.DIRECTIVE, MemoryType.RELATIONSHIP, MemoryType.TASK)
            val rows = types.map { type ->
                val text = "M5_${type.name} 合成验收记录"
                MemoryEntity("m5-ui-${type.name}", if (type == MemoryType.TASK)
                    TaskMemory("ACTIVE", "核对合成校验码", "校验码一致", null, text).encode() else text, type = type)
            }
            for (type in types) {
                inst.runOnMainSync { activity.setContent { ZeroTheme {
                    MemoryManageContent(MemoryUiState(all = rows, typeFilter = type), {}, {}, {}, {}, {})
                } } }
                awaitText("M5_${type.name}")
                if (type == MemoryType.TASK) {
                    awaitText("状态：进行中")
                    assertTrue(nodes().none { it.text?.contains("zero-task:v1") == true })
                }
                assertTrue(nodes().none { it.text?.contains("M5_${types.first { other -> other != type }.name}") == true })
                screenshot(activity, type.name)
            }
            inst.runOnMainSync { activity.finish() }
            activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("acceptancePage", "usage")) as VisualAcceptanceActivity
            awaitText("缓存命中率 90.00%")
            screenshot(activity, "usage")
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
