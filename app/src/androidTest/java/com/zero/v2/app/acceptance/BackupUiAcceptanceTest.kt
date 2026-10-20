package com.zero.v2.app.acceptance

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.data.BackupProgress
import com.zero.v2.app.data.ExportStats
import com.zero.v2.app.data.ImportStats
import com.zero.v2.app.ui.settings.*
import com.zero.v2.app.ui.theme.ZeroTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BackupUiAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            all += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow)
        return all
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(100) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
                ?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(60)
        }
        error("Backup UI text unavailable: $text")
    }
    private fun click(text: String) {
        var node = find(text)
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        inst.waitForIdleSync()
    }
    @Test fun productionSafChooserCancellationDoesNotStartExportOrRestore() {
        var calls = 0
        val owner = androidx.lifecycle.ViewModelStore()
        lateinit var vm: DataExportViewModel
        inst.runOnMainSync {
            vm = DataExportViewModel(object : BackupActions {
                override suspend fun export(uri: android.net.Uri, progress: (BackupProgress) -> Unit): Result<ExportStats> {
                    calls++; error("Cancelled chooser cannot export")
                }
                override suspend fun restore(uri: android.net.Uri, progress: (BackupProgress) -> Unit): Result<ImportStats> {
                    calls++; error("Cancelled chooser cannot restore")
                }
            })
            owner.put("owned", vm)
        }
        McpUiAcceptanceActivity.scale = 1f
        McpUiAcceptanceActivity.content = { ZeroTheme(darkTheme = false, dynamicColor = false) {
            Surface { DataExportScreen({}, vm) }
        } }
        val activity = inst.startActivitySync(Intent(inst.targetContext, McpUiAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as McpUiAcceptanceActivity
        try {
            for (action in listOf("选择备份文件", "导出备份")) {
                click(action)
                var pickerSeen = false
                repeat(100) {
                    if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                        pickerSeen = true; return@repeat
                    }
                    if (!pickerSeen) Thread.sleep(50)
                }
                assertTrue("Real SAF picker must appear", pickerSeen)
                repeat(3) {
                    if (inst.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true) {
                        assertTrue(inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                        Thread.sleep(300)
                    }
                }
                repeat(12) {
                    nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    Thread.sleep(40)
                }
                find("保存与恢复")
                assertFalse(vm.state.value.busy)
                assertEquals(0, calls)
                assertNull(vm.state.value.lastExport)
                assertNull(vm.state.value.lastImport)
            }
        } finally {
            inst.runOnMainSync { activity.finish(); owner.clear() }
            McpUiAcceptanceActivity.content = null
        }
    }
    @Test fun productionBackupPageWindowMatrix() {
        val split = InstrumentationRegistry.getArguments().getString("backupWindow") == "split"
        val group = InstrumentationRegistry.getArguments().getString("backupGroup")
        val rows = JSONArray()
        val root = File(inst.targetContext.filesDir, if (split) "a8-s5-ui-split" else "a8-s5-ui-wide")
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            if (group != null && group != "$scale-$dark-$dynamic") continue
            val state = mutableStateOf(DataExportState())
            val confirmation = mutableStateOf(false)
            var restored = false
            var cancelled = false
            var backed = false
            var contrast = 0.0
            McpUiAcceptanceActivity.scale = scale
            McpUiAcceptanceActivity.content = {
                ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect {
                        contrast = minOf(androidx.core.graphics.ColorUtils.calculateContrast(scheme.onSurface.toArgb(),
                            scheme.surfaceContainerLow.toArgb()), androidx.core.graphics.ColorUtils.calculateContrast(
                            scheme.onSurfaceVariant.toArgb(), scheme.surfaceContainerLow.toArgb()))
                    }
                    Surface(color = scheme.background, contentColor = scheme.onBackground) {
                        BackupPage(state.value, { backed = true }, {}, { confirmation.value = true }, { cancelled = true })
                        if (confirmation.value) BackupRestoreConfirmation({ confirmation.value = false }) {
                            restored = true; confirmation.value = false
                        }
                    }
                }
            }
            val activity = inst.startActivitySync(Intent(inst.targetContext, McpUiAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as McpUiAcceptanceActivity
            try {
                if (split) SplitWindowAcceptance.ensure(activity)
                val width = activity.resources.configuration.screenWidthDp
                assertTrue(if (split) activity.isInMultiWindowMode && width in 280..599 else width >= 600)
                assertEquals(scale, activity.resources.configuration.fontScale, .01f)
                assertTrue("Body text and card surface must reach 4.5", contrast >= 4.5)
                val folder = File(root, "$scale-$dark-$dynamic").apply { mkdirs() }
                fun shot(name: String) {
                    Thread.sleep(120)
                    val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
                    File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                find("保存与恢复"); shot("idle")
                click("选择备份文件")
                find("恢复这个备份？"); shot("confirmation")
                click("取消")
                assertFalse(restored)
                click("选择备份文件"); click("确认恢复")
                assertTrue(restored)
                inst.runOnMainSync { state.value = DataExportState(busy = true, progress = BackupProgress("正在读取备份")) }
                find("取消操作")
                nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                find("取消操作"); shot("reading")
                click("返回"); find("备份正在处理"); click("留在页面")
                assertFalse(backed)
                click("取消操作"); assertTrue(cancelled)
                inst.runOnMainSync { state.value = DataExportState(busy = true,
                    progress = BackupProgress("正在事务恢复，请等待完成", false)) }
                find("正在事务恢复，请等待完成"); shot("committing")
                assertTrue(nodes().none { it.isVisibleToUser && it.text?.toString() == "取消操作" })
                inst.runOnMainSync { state.value = DataExportState(error = "独立提供方写入失败，目标文件可能不完整，请重新导出") }
                find("独立提供方写入失败，目标文件可能不完整，请重新导出"); shot("error")
                inst.runOnMainSync { state.value = DataExportState(lastExport = ExportStats(1,2,3,4,5),
                    lastImport = ImportStats(1,2,3,4,5,3)) }
                find("恢复完成"); shot("success")
                val tail = "恢复不包含完整设备配置；记忆语义向量需等待可用的嵌入服务回填。"
                val bounds = android.graphics.Rect().also(find(tail)::getBoundsInScreen)
                val location = IntArray(2).also(activity.window.decorView::getLocationOnScreen)
                assertTrue(bounds.bottom <= location[1] + activity.window.decorView.height)
                shot("tail")
                rows.put(JSONObject().put("widthDp", width).put("fontScale", scale).put("dark", dark)
                    .put("dynamic", dynamic).put("multiWindow", activity.isInMultiWindowMode)
                    .put("contrast", contrast).put("confirmationGuard", true).put("busyBackGuard", true)
                    .put("cancelRead", true).put("commitNoCancel", true).put("tailReachable", true))
            } finally {
                inst.runOnMainSync { activity.finish() }
                McpUiAcceptanceActivity.content = null
                McpUiAcceptanceActivity.scale = 1f
            }
        }
        assertTrue("Selected backup matrix group must exist", rows.length() > 0)
        File(root, if (group == null) "matrix.json" else "matrix-$group.json").writeText(rows.toString(2))
    }
}
