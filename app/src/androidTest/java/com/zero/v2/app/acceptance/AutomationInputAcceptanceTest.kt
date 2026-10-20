package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.automation.AutomationScreen
import com.zero.v2.app.ui.automation.CreateAutomationScreen
import com.zero.v2.app.ui.automation.AutomationViewModel
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.data.entity.AutomationEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real 200% resource split, isolated list/input; no cloud or personal writes. */
class AutomationInputAcceptanceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun mount(activity:VisualAcceptanceActivity,vm:AutomationViewModel,create:Boolean,dark:Boolean=false,dynamic:Boolean=false) {
        inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=dark,dynamicColor=dynamic) {
            if(create) CreateAutomationScreen({}, {}, vm) else AutomationScreen({}, {}, vm)
        } } }
    }
    private fun nodes():List<AccessibilityNodeInfo> {
        val out=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
        visit(inst.uiAutomation.rootInActiveWindow);return out
    }
    private fun find(label:String):AccessibilityNodeInfo {
        repeat(60) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label) }?.let { return it }
            if(attempt%5==4) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Missing isolated input/list UI: $label")
    }
    private fun owner(node:AccessibilityNodeInfo):AccessibilityNodeInfo {
        var n=node;while(!n.isClickable)n=requireNotNull(n.parent);return n
    }
    private fun click(label:String) { assertTrue(owner(find(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(200) }
    private fun start(scale:Float=2f):VisualAcceptanceActivity {
        VisualAcceptanceActivity.fontScaleForNextLaunch=scale
        return (inst.startActivitySync(SplitWindowAcceptance.launchIntent(inst.targetContext)) as VisualAcceptanceActivity).also {
            SplitWindowAcceptance.ensure(it);assertEquals(scale,it.resources.configuration.fontScale,0f)
            assertTrue(it.isInMultiWindowMode)
        }
    }
    @Test fun lastLongTaskClearsFabAndListPositionSurvivesRecreation()=runBlocking {
        val tasks=(1..24).map { AutomationEntity("list-$it","隔离长任务第${it}项：完整标题保留阅读和操作","隔离说明","{}") }
        AutomationCommandAcceptanceTest().fixture({ FunctionResult.failure("隔离拒绝") },tasks) { vm,_ ->
            var activity=start()
            try {
                mount(activity,vm,false)
                val tail=tasks.last().title
                repeat(60) {
                    val scroll=nodes().firstOrNull { it.isScrollable }
                    if(scroll==null || scroll.actionList.none { a->a.id==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })return@repeat
                    assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));Thread.sleep(80)
                }
                val target=owner(find(tail));val rect=Rect().also(target::getBoundsInScreen)
                val fab=Rect().also(owner(find("创建任务"))::getBoundsInScreen)
                val window=SplitWindowAcceptance.bounds(activity);val density=activity.resources.displayMetrics.density
                assertTrue(window.contains(rect));assertTrue(rect.width()/density>=48 && rect.height()/density>=48)
                assertFalse("Last task must not overlap FAB",Rect.intersects(rect,fab))
                click(tail);find("任务详情");click("关闭")
                activity=SplitWindowAcceptance.recreate(activity,2f);mount(activity,vm,false)
                Thread.sleep(300)
                assertTrue("Restore must retain visible last task without scrolling",nodes().any { it.isVisibleToUser && it.text?.toString()==tail })
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
    @Test fun descriptionKeyboardAndDeniedGenerationRetainInputAcrossRecreation()=runBlocking {
        AutomationCommandAcceptanceTest().fixture({ FunctionResult.failure("隔离生成拒绝") }) { vm,_ ->
            var activity=start()
            try {
                mount(activity,vm,true);find("任务需求")
                val field=nodes().first { it.isEditable }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                repeat(30) { if(inst.uiAutomation.windows.none { w->w.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })Thread.sleep(100) }
                assertTrue("Actual input method must be shown",inst.uiAutomation.windows.any { it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })
                val input="隔离需求：每天整理工作记录，先确认卡片再执行"
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,input) }))
                val button=owner(find("生成任务卡片"));val rect=Rect().also(button::getBoundsInScreen)
                val ime=inst.uiAutomation.windows.first { it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                assertFalse("Generation must be usable above real IME",Rect.intersects(rect,Rect().also(ime::getBoundsInScreen)))
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                repeat(30) { if(vm.state.value.message==null)Thread.sleep(100) }
                assertTrue(vm.state.value.message?.contains("隔离生成拒绝")==true)
                assertTrue(nodes().any { it.isEditable && it.text?.toString()==input })
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                activity=SplitWindowAcceptance.recreate(activity,2f);mount(activity,vm,true)
                find("任务需求");find(input)
                assertNull(vm.draft.value);assertFalse(vm.drafting.value)
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
    @Test fun largeEditorOptionsAndRejectedIntervalKeepDraft()=runBlocking {
        val task=AutomationEntity("large-edit","隔离调度任务","隔离说明","{}")
        AutomationCommandAcceptanceTest().fixture({ FunctionResult.failure("隔离调度拒绝") },listOf(task)) { vm,_ ->
            var activity=start()
            try {
                mount(activity,vm,false);click(task.title);click("编辑");find("编辑任务")
                val title=nodes().first { it.isEditable }
                assertTrue(title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"隔离大字号标题") }))
                for(label in listOf("手动","每日","间隔")) {
                    val option=owner(find(label));val rect=Rect().also(option::getBoundsInScreen)
                    val density=activity.resources.displayMetrics.density
                    assertTrue("Large editor option >=48dp",rect.width()/density>=48 && rect.height()/density>=48)
                    assertTrue(option.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(200)
                }
                find("间隔（分钟）")
                val value=nodes().last { it.isEditable }
                assertTrue(value.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"45") }))
                click("保存")
                repeat(30) { if(vm.state.value.message==null)Thread.sleep(100) }
                assertTrue(vm.state.value.message?.contains("隔离调度拒绝")==true)
                activity=SplitWindowAcceptance.recreate(activity,2f);mount(activity,vm,false)
                find("编辑任务");find("隔离大字号标题");find("间隔（分钟）");find("45")
                val save=owner(find("保存"));val rect=Rect().also(save::getBoundsInScreen)
                val density=activity.resources.displayMetrics.density
                assertTrue(rect.width()/density>=48 && rect.height()/density>=48)
                click("取消");find("任务详情")
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
    @Test fun listEditorAndRequirementFollowAllEightThemes()=runBlocking {
        val reports=org.json.JSONArray()
        for(scale in listOf(1f,2f)) for(dark in listOf(false,true)) for(dynamic in listOf(false,true)) {
            val tasks=listOf(
                AutomationEntity("visual-active","隔离任务：阅读完整工作记录","隔离说明","{}"),
                AutomationEntity("visual-paused","隔离暂停任务：保留调度与阅读入口","隔离说明","{}",status="PAUSED"))
            AutomationCommandAcceptanceTest().fixture({ FunctionResult.failure("隔离拒绝") },tasks) { vm,_ ->
                val activity=start(scale)
                try {
                    val ownBounds=SplitWindowAcceptance.bounds(activity)
                    val density=activity.resources.displayMetrics.density
                    val width=activity.resources.displayMetrics.widthPixels/density
                    assertTrue(width in 280f..599f)
                    fun shot(name:String) {
                        Thread.sleep(500)
                        val ui=inst.uiAutomation;val root=requireNotNull(ui.rootInActiveWindow)
                        assertEquals("com.zero.v2",root.packageName.toString())
                        val bounds=Rect().also(ui.windows.first { it.id==root.windowId }::getBoundsInScreen)
                        assertTrue("Capture only owned fixture window",ownBounds.contains(bounds))
                        val screen=requireNotNull(ui.takeScreenshot())
                        val crop=android.graphics.Bitmap.createBitmap(screen,bounds.left,bounds.top,bounds.width(),bounds.height())
                        try {
                            val dest=java.io.File(inst.targetContext.filesDir,"a8-automation-input/$scale-$dark-$dynamic/$name.png")
                            dest.parentFile!!.mkdirs();dest.outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                        } finally { crop.recycle();screen.recycle() }
                    }
                    mount(activity,vm,false,dark,dynamic)
                    find(tasks.last().title);find("创建任务");shot("list")
                    click(tasks.last().title);click("编辑");find("编辑任务")
                    for(label in listOf("手动","每日","间隔")) {
                        val option=owner(find(label));val rect=Rect().also(option::getBoundsInScreen)
                        assertTrue(rect.width()/density>=48 && rect.height()/density>=48)
                        assertTrue(option.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(150)
                    }
                    find("间隔（分钟）");find("保存");find("取消");shot("editor")
                    click("取消");click("关闭")
                    mount(activity,vm,true,dark,dynamic);find("任务需求")
                    val field=nodes().first { it.isEditable }
                    assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"隔离需求：整理工作记录并确认任务卡片") }))
                    val generate=owner(find("生成任务卡片"));val rect=Rect().also(generate::getBoundsInScreen)
                    assertTrue(rect.width()/density>=48 && rect.height()/density>=48)
                    shot("requirement")
                    reports.put(org.json.JSONObject().put("fontScale",scale).put("dark",dark).put("dynamic",dynamic)
                        .put("widthDp",width).put("actualMultiWindow",true).put("minimumTouchDp",48))
                } finally { inst.runOnMainSync { activity.finish() } }
            }
        }
        java.io.File(inst.targetContext.filesDir,"a8-automation-input/report.json").writeText(org.json.JSONObject()
            .put("passed",true).put("conditions",reports).put("scope","production list/editor/requirement with synthetic tasks")
            .put("providerRequests",0).put("personalDataWrites",0).toString(2))
    }
}
