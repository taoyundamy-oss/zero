package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.automation.AutomationScreen
import com.zero.v2.app.ui.automation.AutomationViewModel
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.data.entity.AutomationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual list/detail UI with isolated synthetic task and refusing dispatcher. */
class AutomationDetailAcceptanceTest {
    private val task=AutomationEntity("a8-detail", "验收任务", "隔离说明", "{}")
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun mount(activity:VisualAcceptanceActivity, vm:AutomationViewModel) {
        inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=false,dynamicColor=false) { AutomationScreen({}, {}, vm) } } }
    }
    private fun find(label:String):AccessibilityNodeInfo {
        repeat(50) {
            val out=mutableListOf<AccessibilityNodeInfo>()
            fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
            visit(inst.uiAutomation.rootInActiveWindow)
            out.firstOrNull { it.isVisibleToUser && (it.text?.toString()?.contains(label)==true || it.contentDescription?.toString()==label) }?.let { return it }
            Thread.sleep(100)
        }
        error("Missing synthetic task UI: $label")
    }
    private fun click(label:String):AccessibilityNodeInfo {
        var owner=find(label);while(!owner.isClickable) owner=requireNotNull(owner.parent)
        assertTrue(owner.performAction(AccessibilityNodeInfo.ACTION_CLICK));return owner
    }
    @Test fun processToggleHasCompleteTouchTarget()=runBlocking {
        AutomationCommandAcceptanceTest().fixture({ FunctionResult.failure("隔离拒绝") }, listOf(task)) { vm,_ ->
            VisualAcceptanceActivity.fontScaleForNextLaunch=1f
            val activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                SplitWindowAcceptance.ensure(activity);mount(activity,vm);click("验收任务")
                var owner=find("展开");while(!owner.isClickable)owner=requireNotNull(owner.parent)
                val rect=Rect().also(owner::getBoundsInScreen);val density=activity.resources.displayMetrics.density
                assertTrue("Process toggle must be >=48dp",rect.width()/density>=48 && rect.height()/density>=48)
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
    @Test fun detailAndExpandedProcessSurviveRecreation()=runBlocking {
        AutomationCommandAcceptanceTest().fixture({ FunctionResult.failure("隔离拒绝") }, listOf(task)) { vm,_ ->
            VisualAcceptanceActivity.fontScaleForNextLaunch=1f
            var activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                SplitWindowAcceptance.ensure(activity);mount(activity,vm);click("验收任务");click("展开");find("收起")
                activity=SplitWindowAcceptance.recreate(activity,1f);mount(activity,vm)
                find("立即执行");find("收起")
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
}
