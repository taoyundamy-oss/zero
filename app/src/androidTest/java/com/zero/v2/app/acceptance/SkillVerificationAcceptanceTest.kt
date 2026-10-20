package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.skills.SkillStore
import com.zero.v2.app.skills.SkillVerificationCard
import com.zero.v2.app.skills.SkillVerificationViewModel
import com.zero.v2.core.bus.SystemBusImpl
import com.zero.v2.core.data.db.ZeroDatabase
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real production verification UI backed exclusively by isolated files/in-memory Room. */
class SkillVerificationAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(inst.uiAutomation.rootInActiveWindow)
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(50) {
            nodes().firstOrNull { it.text?.contains(text) == true }?.let { return it }
            Thread.sleep(100)
        }
        error("UI text absent: $text")
    }
    private fun click(node: AccessibilityNodeInfo) {
        val label = node.text?.toString()
        repeat(50) {
            var target = nodes().firstOrNull { it.text?.toString() == label } ?: node
            while (!target.isClickable && target.parent != null) target = target.parent
            if (target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            Thread.sleep(100)
        }
        error("Clickable UI not ready: $label")
    }
    @Test fun humanCanInspectAndConfirmVersionWithoutChangingPhoneData() {
        val root = File(inst.targetContext.cacheDir,"p7-skill-ui")
        val db = Room.inMemoryDatabaseBuilder(inst.targetContext, ZeroDatabase::class.java).build()
        val store = SkillStore(root)
        val body = listOf("用途","触发条件","前提","工具","步骤","成功判据","失败恢复")
            .joinToString("\n") { "## $it\n合成技能" }
        store.write("p7-ui", "", body, "synthetic")
        val vm = SkillVerificationViewModel(store, db.kbDao(), SystemBusImpl())
        val activity = inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            inst.runOnMainSync {
                activity.viewModelStore.put("p7-skills", vm)
                activity.setContent { MaterialTheme { SkillVerificationCard(vm) } }
            }
            click(find("p7-ui"))
            find("核验 p7-ui")
            val editor = nodes().first { it.className == "android.widget.EditText" }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"合成独立任务核验通过")
            }))
            click(find("确认此版本验证通过"))
            find("已记录人工核验")
            assertEquals("user_confirmed",SkillStore(root).read("p7-ui")!!.status)
        } finally {
            inst.runOnMainSync { activity.finish() }
            inst.waitForIdleSync()
            db.close()
            assertTrue(root.deleteRecursively())
        }
    }
}
