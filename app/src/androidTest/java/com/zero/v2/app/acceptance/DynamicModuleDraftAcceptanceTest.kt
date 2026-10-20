package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DynamicModuleActions
import com.zero.v2.app.ui.settings.DynamicModulesViewModel
import com.zero.v2.app.ui.settings.DynModuleDetailContent
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.data.entity.DynamicModuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual production form + VM/store, isolated data; no daily config/deployment actions. */
class DynamicModuleDraftAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val module = DynamicModuleEntity("a8-detail-isolated", source = "# isolated", createdAt = 1, state = "ACTIVE",
        configSchema = """[{"key":"name","label":"隔离名称","type":"text","default":"original"},{"key":"enabled","label":"隔离开关","type":"toggle","default":false},{"key":"limit","label":"隔离数量","type":"number","default":5}]""")
    private val actions = object : DynamicModuleActions {
        override val discoveredCount = MutableStateFlow(0)
        override suspend fun mount(module: DynamicModuleEntity) = error("Must not start fixture")
        override suspend fun unmount(module: DynamicModuleEntity) = false
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null || !n.refresh()) return
            out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow); return out
    }
    private fun textField(): AccessibilityNodeInfo {
        repeat(60) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && it.isEditable }?.let { return it }
            if (attempt % 4 == 3) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Isolated form field unavailable")
    }
    private fun edit(value: String) {
        assertTrue(textField().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        Thread.sleep(100)
    }
    private fun start(): VisualAcceptanceActivity {
        VisualAcceptanceActivity.fontScaleForNextLaunch = 1f
        return (inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity).also { SplitWindowAcceptance.ensure(it) }
    }
    private fun mount(activity: VisualAcceptanceActivity, vm: DynamicModulesViewModel) {
        inst.runOnMainSync { activity.setContent { ZeroTheme {
            val state by vm.state.collectAsState()
            LaunchedEffect(Unit) { vm.loadDetail(module.id) }
            DynModuleDetailContent(module.id, state, {}, { vm.loadDetail(module.id) }, vm::clearActionMessage,
                { vm.saveConfig(module.id, it) }, { Text("隔离部署观察") })
        } } }
    }
    @Test fun unsavedTextSurvivesActualActivityRecreation() = runBlocking {
        DynamicModulesStateAcceptanceTest().fixture(actions, lookup = { module }) { vm, configs ->
            var activity = start()
            try {
                mount(activity, vm); edit("isolated draft")
                assertEquals(null, configs.get(module.id))
                activity = SplitWindowAcceptance.recreate(activity, 1f); mount(activity, vm)
                assertEquals("Unsaved draft must survive actual recreation", "isolated draft", textField().text.toString())
                assertEquals(null, configs.get(module.id))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
    @Test fun rereadMustNotReplaceUnsavedTextWithNewStoredValues() = runBlocking {
        DynamicModulesStateAcceptanceTest().fixture(actions, lookup = { module }) { vm, configs ->
            val activity = start()
            try {
                mount(activity, vm); edit("isolated draft")
                configs.saveNow(module.id, """{"name":"new stored","enabled":false,"limit":5}""")
                withContext(Dispatchers.Main) { vm.loadDetail(module.id) }
                withTimeout(2000) { while (vm.state.value.detailLoading || vm.state.value.detailValues["name"] != "new stored") delay(10) }
                delay(100)
                assertEquals("Reread must retain user's unsaved draft", "isolated draft", textField().text.toString())
                assertEquals("new stored", org.json.JSONObject(configs.get(module.id)!!).getString("name"))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(80) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return it }
            if (attempt % 4 == 3) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Missing isolated config control: $label")
    }
    @Test fun restoredTextToggleAndNumberAreTheExactValuesSavedOnActivationFailure() = runBlocking {
        DynamicModulesStateAcceptanceTest().fixture(actions, lookup = { module }) { vm, configs ->
            var activity = start()
            try {
                mount(activity, vm); edit("saved isolated draft")
                assertTrue(find("切换 隔离开关").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(find("编辑 隔离数量").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "-2.5") }))
                activity = SplitWindowAcceptance.recreate(activity, 1f); mount(activity, vm)
                var button = find("保存配置"); while (!button.isClickable) button = requireNotNull(button.parent)
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(2000) { while (vm.state.value.saving || vm.state.value.actionError == null) delay(10) }
                val saved = org.json.JSONObject(configs.get(module.id)!!)
                assertEquals("saved isolated draft", saved.getString("name")); assertTrue(saved.getBoolean("enabled"))
                assertEquals(-2.5, saved.getDouble("limit"), 0.0)
                assertTrue(vm.state.value.actionError!!.contains("停止模块失败"))
                // The failure must not reset the visible text control when navigating back to the form.
                repeat(12) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(50) }
                assertEquals("saved isolated draft", textField().text.toString())
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }

}
