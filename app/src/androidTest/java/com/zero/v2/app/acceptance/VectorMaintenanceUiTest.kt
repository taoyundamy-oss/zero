package com.zero.v2.app.acceptance

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.*
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.services.embedding.VectorDomain
import com.zero.v2.services.embedding.VectorIndexWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class VectorMaintenanceUiTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser) add(n)
            repeat(n.childCount) { visit(n.getChild(it)) }
        }
        visit(inst.uiAutomation.rootInActiveWindow)
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(60) {
            nodes().firstOrNull { it.text?.toString() == text }?.let { return it }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Missing maintenance UI: $text")
    }
    private fun click(text: String) {
        var n = find(text)
        while (!n.isClickable && n.parent != null) n = n.parent
        assertTrue(n.isEnabled); assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        inst.waitForIdleSync(); Thread.sleep(150)
    }
    private class Actions : VectorMaintenanceActions {
        var active = "current"
        var calls = 0
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun status(domain: VectorDomain) = VectorIndexWorker.Status(active, null, 0, 2,
            activeSpaceMatches = false, rollbackTarget = "previous")
        override suspend fun rollback(domain: VectorDomain, expected: String): Boolean {
            calls++
            wait?.await()
            check(active == expected) { "vector_base_changed" }
            active = "previous"
            return true
        }
    }
    private fun fixture(block: (VectorMaintenanceViewModel, Actions) -> Unit) {
        val owner = ViewModelStore(); val actions = Actions()
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        lateinit var vm: VectorMaintenanceViewModel
        try {
            inst.runOnMainSync {
                vm = VectorMaintenanceViewModel(actions); owner.put("maintenance", vm)
                activity.setContent { ZeroTheme { VectorMaintenanceScreen({}, vm) } }
            }
            find("状态已刷新；未构建索引或切换模型")
            block(vm, actions)
        } finally { inst.runOnMainSync { owner.clear(); activity.finish() } }
    }
    @Test fun confirmationCancelAndStaleVersionAreVisible() = fixture { _, actions ->
        click("回滚记忆索引"); click("取消"); assertEquals(0, actions.calls)
        click("回滚记忆索引"); click("确认回滚")
        find("已回滚记忆索引；原记忆与模型配置保持不变")
        assertEquals(1, actions.calls)
        click("回滚记忆索引")
        actions.active = "external-change"
        click("确认回滚")
        find("操作未完成：索引、来源或验收条件可能已变化，请刷新后重试")
        assertEquals("external-change", actions.active)
    }
    @Test fun duplicateSubmissionIsBlockedWhileRollbackRuns() = fixture { vm, actions ->
        actions.wait = CompletableDeferred()
        inst.runOnMainSync { vm.rollback(VectorDomain.MEMORY, "current"); vm.rollback(VectorDomain.MEMORY, "current") }
        runBlocking { withTimeout(5000) { while (actions.calls == 0) delay(10) } }
        assertEquals(1, actions.calls)
        actions.wait!!.complete(Unit)
        find("已回滚记忆索引；原记忆与模型配置保持不变")
        assertEquals(1, actions.calls)
    }
}
