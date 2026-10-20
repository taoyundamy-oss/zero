package com.zero.v2.app.acceptance

import android.content.Intent
import android.content.IntentFilter
import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.LinkifiedText
import com.zero.v2.app.ui.settings.*
import com.zero.v2.core.data.entity.CharacterEntity
import com.zero.v2.app.ui.settings.CharacterStateUiState
import com.zero.v2.app.ui.theme.ZeroTheme
import org.junit.Assert.*
import org.junit.Test

class PersistentUiAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(n: AccessibilityNodeInfo?) { if (n == null) return; if(n.isVisibleToUser) add(n); repeat(n.childCount) { visit(n.getChild(it)) } }
        visit(inst.uiAutomation.rootInActiveWindow)
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(70) { nodes().firstOrNull { it.text?.contains(text) == true || it.contentDescription?.contains(text) == true }?.let { return it }; nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(100) }
        error("Missing synthetic UI: $text")
    }
    @Test fun fullCharacterPageExplainsStateAndDispatchesReset() {
        val activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val calls=mutableListOf<String>()
        val actions=CharacterPageActions({}, {}, {}, {_,_,_->}, {}, {}, {},
            {calls.add("affinity")},{calls.add("mood")},{},{})
        try {
            inst.runOnMainSync { activity.setContent { ZeroTheme {
                CharacterManageContent(listOf(CharacterEntity(id="synthetic",name="合成角色",systemPrompt="synthetic",isActive=true)),
                    CharacterEditState.Hidden,CharacterStateUiState(activeCharacterId="synthetic",activeCharacterName="合成角色",loaded=true),
                    CharacterActionState(loading=false),{},actions)
            } } }
            find("角色与表达")
            find("初识 → 熟络 → 亲近 → 深契")
            for(label in listOf("重置亲密度","重置情绪")) {
                var node=find(label)
                while(!node.isClickable && node.parent!=null) node=node.parent
                assertTrue(node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            inst.waitForIdleSync()
            assertEquals(listOf("affinity","mood"),calls)
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun linkDispatchesViewIntentAndRoleRulesAreVisible() {
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val filter = IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }
        val monitor = inst.addMonitor(filter, Instrumentation.ActivityResult(0, null), true)
        try {
            inst.runOnMainSync { activity.setContent { ZeroTheme {
                Column(Modifier.statusBarsPadding().padding(24.dp)) {
                    LinkifiedText("[验收链接](https://example.com/p7)", color=MaterialTheme.colorScheme.onSurface, linkColor=MaterialTheme.colorScheme.primary)
                    CharacterStateExplanation(CharacterStateUiState(loaded=true, moodLabel="关切", moodExpiresAt=System.currentTimeMillis()+60_000, lastAffinityChangeAt=System.currentTimeMillis()))
                }
            } } }
            find("初识 → 熟络 → 亲近 → 深契")
            find("聊天次数不等于升级进度")
            find("平静、温和、关切、欢快")
            find("最近有记录会话的情绪：关切")
            find("升阶冷却剩余")
            val link = find("验收链接")
            val rect=android.graphics.Rect().also(link::getBoundsInScreen)
            val now=android.os.SystemClock.uptimeMillis()
            assertTrue(inst.uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,rect.exactCenterX(),rect.exactCenterY(),0),true))
            assertTrue(inst.uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(now,now+60,android.view.MotionEvent.ACTION_UP,rect.exactCenterX(),rect.exactCenterY(),0),true))
            repeat(30) { if(monitor.hits==0) Thread.sleep(100) }
            assertEquals("Production link dispatches ACTION_VIEW (browser network intercepted)",1,monitor.hits)
        } finally { inst.removeMonitor(monitor); inst.runOnMainSync { activity.finish() } }
    }
}

