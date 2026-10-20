package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
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

/** Actual editor; controlled denial, no personal tasks or provider calls. */
class AutomationEditAcceptanceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val task=AutomationEntity("a8-edit","隔离编辑任务","隔离说明","{}")
    private fun mount(activity:VisualAcceptanceActivity,vm:AutomationViewModel) {
        inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=false,dynamicColor=false) { AutomationScreen({}, {}, vm) } } }
    }
    private fun nodes():List<AccessibilityNodeInfo> {
        val out=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
        visit(inst.uiAutomation.rootInActiveWindow);return out
    }
    private fun find(label:String):AccessibilityNodeInfo {
        repeat(50) {
            val current=nodes().filter { it.isVisibleToUser };
            (current.firstOrNull { it.text?.toString()==label || it.contentDescription?.toString()==label }
                ?:current.firstOrNull { it.text?.toString()?.contains(label)==true })?.let { return it }
            Thread.sleep(100)
        }
        error("Missing isolated editor UI: $label")
    }
    private fun click(label:String) {
        var owner=find(label);while(!owner.isClickable) owner=requireNotNull(owner.parent)
        assertTrue(owner.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(200)
    }
    @Test fun cancelledEditReturnsToExpandedDetail()=runBlocking {
        AutomationCommandAcceptanceTest().fixture({ FunctionResult.failure("隔离拒绝") },listOf(task)) { vm,_ ->
            VisualAcceptanceActivity.fontScaleForNextLaunch=1f
            val activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                SplitWindowAcceptance.ensure(activity);mount(activity,vm)
                click(task.title);click("展开");find("收起");click("编辑");find("编辑任务");click("取消")
                find("任务详情");find("收起")
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
    @Test fun editedTitleSurvivesRecreationAndRejectedSave()=runBlocking {
        AutomationCommandAcceptanceTest().fixture({ FunctionResult.failure("隔离保存拒绝") },listOf(task)) { vm,reads ->
            VisualAcceptanceActivity.fontScaleForNextLaunch=1f
            var activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                SplitWindowAcceptance.ensure(activity);mount(activity,vm);click(task.title);click("编辑")
                find("编辑任务")
                val field=nodes().first { it.isEditable }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"隔离修改后的标题") }))
                find("隔离修改后的标题")
                activity=SplitWindowAcceptance.recreate(activity,1f);mount(activity,vm)
                find("编辑任务");find("隔离修改后的标题")
                Thread.sleep(200)
                val readsBeforeSave=reads.get()
                click("保存")
                find("隔离保存拒绝");find("编辑任务");find("隔离修改后的标题")
                assertEquals("Rejected save must not refresh",readsBeforeSave,reads.get())
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
}
