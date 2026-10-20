package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.AgentBehaviorScreen
import com.zero.v2.app.ui.settings.AgentBehaviorViewModel
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.TokenUsage
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.ModuleConfigEntity
import com.zero.v2.core.data.store.acceptanceConfigStore
import com.zero.v2.core.trace.AgentTraceManager
import com.zero.v2.framework.memory.MemoryModuleConfig
import com.zero.v2.services.agent.AgentRuntimeConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real resource font scale and split window; isolated SQLite, no provider requests. */
class AgentBehaviorVisualAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; result.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
        visit(inst.uiAutomation.rootInActiveWindow); return result
    }
    private fun find(label: String, description: Boolean = false, backward: Boolean = false): AccessibilityNodeInfo {
        repeat(100) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && (if (description) it.contentDescription?.toString() else it.text?.toString()) == label }?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(70)
        }
        error("Missing isolated behavior control: $label")
    }
    private fun click(label: String, description: Boolean = false, backward: Boolean = false) {
        repeat(30) {
            find(label, description, backward)
            val n = owner(label, description)
            if (n != null && n.isEnabled) {
                n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(300); return }
            }
            Thread.sleep(100)
        }
        error("No enabled click owner: $label")
    }
    // Compose may replace virtual IDs after state commits; walk one fresh tree with its ancestors.
    private fun owner(label: String, description: Boolean = false): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun visit(n: AccessibilityNodeInfo?, ancestor: AccessibilityNodeInfo?) {
            if (n == null || !n.refresh()) return
            val candidate = if (!n.isEnabled || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) n else ancestor
            if (n.isVisibleToUser && (if (description) n.contentDescription?.toString() else n.text?.toString()) == label) found = candidate
            for (i in 0 until n.childCount) visit(n.getChild(i), candidate)
        }
        visit(inst.uiAutomation.rootInActiveWindow, null); return found
    }
    private fun shot(activity: VisualAcceptanceActivity, name: String) {
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
        val f = File(inst.targetContext.filesDir, "a8-behavior-ui/$name.png"); f.parentFile!!.mkdirs()
        f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun setDraft(text: String) {
        val field = nodes().first { it.isEditable && it.isVisibleToUser }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })); Thread.sleep(200)
    }
    @Test fun actualBehaviorPageInEightSplitFontThemeConditions() = runBlocking {
        val results = org.json.JSONArray()
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            val db = Room.inMemoryDatabaseBuilder(inst.targetContext, ZeroDatabase::class.java).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val owner = ViewModelStore()
            val report = AgentRuntimeConfig(); val memory = MemoryModuleConfig().apply { enableReflection = true }
            var activity: VisualAcceptanceActivity? = null
            val tag = "${scale.toInt()}-$dark-$dynamic"
            try {
                db.moduleConfigDao().upsert(ModuleConfigEntity("agent_behavior", "{\"mode\":\"OFF\"}"))
                val vm = withContext(Dispatchers.Main) { AgentBehaviorViewModel(report, memory, MutableStateFlow(TokenUsage()), acceptanceConfigStore(db.moduleConfigDao(), scope), AgentTraceManager(db.agentTraceEventDao()), {}).also { owner.put("matrix", it) } }
                withTimeout(3000) { while (!vm.state.value.behaviorLoaded) delay(10) }
                VisualAcceptanceActivity.fontScaleForNextLaunch = scale
                activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                fun mount() { inst.runOnMainSync { activity!!.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) { AgentBehaviorScreen({}, vm) } } } }
                SplitWindowAcceptance.ensure(activity!!); mount(); find("自主行为")
                assertEquals(scale, activity!!.resources.configuration.fontScale, 0f)
                assertTrue(activity!!.isInMultiWindowMode)
                val widthDp = SplitWindowAcceptance.bounds(activity!!).width() / activity!!.resources.displayMetrics.density
                assertTrue("Must be actual narrow split: $widthDp", widthDp in 280f..599f)
                shot(activity!!, "$tag-head")
                for (mode in listOf("智能", "始终在线", "关闭")) {
                    click(mode, backward = true)
                    withTimeout(3000) { while (vm.state.value.behaviorSaving) delay(10) }
                }
                assertEquals("OFF", vm.state.value.mode)
                click("报点随发自主行动")
                withTimeout(3000) { while (vm.state.value.behaviorSaving) delay(10) }
                assertTrue(vm.state.value.selfDriveOnTick)
                // Every editor is reachable and owns a full 48dp minimum click target.
                for (label in listOf("基础间隔", "随机偏移", "间隔倍数", "递增级数上限", "在场静默窗", "记忆整理时间", "图谱提取阈值")) {
                    find("编辑$label", description = true)
                    var n = requireNotNull(owner("编辑$label", description = true))
                    n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); Thread.sleep(100)
                    val bounds = Rect(); n.refresh(); n.getBoundsInScreen(bounds)
                    // AX bounds are clipped by the viewport even when a partially visible target is discoverable.
                    if (bounds.height() < 48 * activity!!.resources.displayMetrics.density - 1) {
                        val pane = SplitWindowAcceptance.bounds(activity!!)
                        val x = pane.left + 8 * activity!!.resources.displayMetrics.density
                        val y = pane.bottom - 80 * activity!!.resources.displayMetrics.density
                        val now = android.os.SystemClock.uptimeMillis()
                        for (step in 0..12) {
                            val action = if (step == 0) android.view.MotionEvent.ACTION_DOWN else if (step == 12) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_MOVE
                            val event = android.view.MotionEvent.obtain(now, now + step * 20L, action, x, y - step * 20 * activity!!.resources.displayMetrics.density, 0)
                            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            try { assertTrue(inst.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                        }
                        Thread.sleep(300); find("编辑$label", description = true, backward = true)
                        n = requireNotNull(owner("编辑$label", description = true))
                        n.getBoundsInScreen(bounds)
                    }
                    assertTrue("Small target: $label $bounds density=${activity!!.resources.displayMetrics.density}", bounds.height() >= 48 * activity!!.resources.displayMetrics.density - 1)
                    assertTrue("Clipped target: $label", SplitWindowAcceptance.bounds(activity!!).contains(bounds))
                    assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200); click("取消")
                }
                click("编辑基础间隔", description = true, backward = true); setDraft("bad")
                find("请输入范围内的数值")
                find("保存"); val save = requireNotNull(owner("保存"))
                assertFalse(save.isEnabled)
                activity = SplitWindowAcceptance.recreate(activity!!, scale); mount(); Thread.sleep(300)
                assertEquals("bad", nodes().first { it.isEditable && it.isVisibleToUser }.text?.toString())
                setDraft("1234"); click("保存")
                withTimeout(3000) { while (vm.state.value.parameterSaving || vm.state.value.parameterMessage == null) delay(10) }
                assertEquals(1234, report.baseIntervalSec)
                assertEquals(1234, org.json.JSONObject(db.moduleConfigDao().getByModuleId("agent_runtime")!!.configJson).getInt("baseIntervalSec"))
                shot(activity!!, "$tag-saved")
                repeat(30) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(60) }
                val footer = "达到预算上限后暂停后续模型调用。Token 用量跨重启累计；费用为应用估算，实际账单以所选服务为准。"
                find(footer); shot(activity!!, "$tag-tail")
                activity = SplitWindowAcceptance.recreate(activity!!, scale); mount(); Thread.sleep(300)
                assertTrue("Tail scroll restoration", nodes().any { it.isVisibleToUser && it.text?.toString() == footer })
                shot(activity!!, "$tag-restored-tail")
                results.put(org.json.JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic).put("widthDp", widthDp)
                    .put("realSplit", true).put("nineEditors", true).put("invalidDraftRestored", true).put("sqliteConfirmed", true).put("tailRestored", true))
                File(inst.targetContext.filesDir, "a8-behavior-ui/report.json").writeText(results.toString(2))
            } finally { inst.runOnMainSync { activity?.finish(); owner.clear() }; scope.cancel(); delay(100); db.close() }
        }
    }
}
