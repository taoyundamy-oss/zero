package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
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

/** Actual settings screen, real VM/SQLite rejection and retry; one device condition, no personal settings. */
class AgentParameterUiAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; result.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
        visit(inst.uiAutomation.rootInActiveWindow); return result
    }
    private fun find(text: String, backward: Boolean = false): AccessibilityNodeInfo {
        repeat(70) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.contains(text) == true }?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(if (backward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(80)
        }
        error("Missing isolated parameter UI: $text")
    }
    private fun click(text: String, backward: Boolean = false) {
        var n = find(text, backward); while (!n.isClickable) n = requireNotNull(n.parent)
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        assertTrue(n.isEnabled); assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200)
    }
    private fun shot(activity: VisualAcceptanceActivity, name: String) {
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
        val f = File(inst.targetContext.filesDir, "a8-behavior-parameters/$name.png"); f.parentFile!!.mkdirs()
        f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun actualNumericEditorShowsSqliteFailureThenExplicitRetryAndConfirmedValue() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(inst.targetContext, ZeroDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val owner = ViewModelStore()
        val report = AgentRuntimeConfig(); val memory = MemoryModuleConfig()
        var activity: VisualAcceptanceActivity? = null
        try {
            db.moduleConfigDao().upsert(ModuleConfigEntity("agent_behavior", "{\"mode\":\"OFF\"}"))
            db.moduleConfigDao().upsert(ModuleConfigEntity("agent_runtime", report.toJson()))
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_ui_reject BEFORE INSERT ON module_configs WHEN NEW.module_id='agent_runtime' BEGIN SELECT RAISE(ABORT, 'isolated rejection'); END") }
            val vm = withContext(Dispatchers.Main) { AgentBehaviorViewModel(report, memory, MutableStateFlow(TokenUsage()), acceptanceConfigStore(db.moduleConfigDao(), scope), AgentTraceManager(db.agentTraceEventDao()), {}).also { owner.put("ui", it) } }
            withTimeout(3000) { while (!vm.state.value.behaviorLoaded) delay(10) }
            VisualAcceptanceActivity.fontScaleForNextLaunch = 1f
            val page = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            activity = page
            inst.runOnMainSync { page.setContent { ZeroTheme(darkTheme = false, dynamicColor = false) { AgentBehaviorScreen({}, vm) } } }
            find("自主行为"); click("90 秒")
            val edit = nodes().first { it.isEditable }
            assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "1234") }))
            click("保存")
            withTimeout(3000) { while (vm.state.value.parameterError == null) delay(10) }
            find("保存参数失败", backward = true); shot(page, "sqlite-failure")
            assertEquals(90, report.baseIntervalSec); assertNull(vm.state.value.parameterMessage)
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER isolated_ui_reject") }
            click("重试保存参数", backward = true)
            withTimeout(3000) { while (vm.state.value.parameterSaving || vm.state.value.parameterMessage == null) delay(10) }
            find("设置已保存", backward = true); shot(page, "confirmed-feedback")
            find("1234 秒"); shot(page, "confirmed-value")
            assertEquals(1234, report.baseIntervalSec)
        } finally {
            inst.runOnMainSync { activity?.finish(); owner.clear() }; scope.cancel(); delay(100); db.close()
        }
    }
}
