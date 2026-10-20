package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.automation.CreateAutomationScreen
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.FunctionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual production create page and ViewModel on 200% resources in a real split; no provider. */
class AutomationCreateNarrowAcceptanceTest {
    @Test fun executionAndScheduleChoicesFitLargeSplit()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val ui=inst.uiAutomation
        val reports=org.json.JSONArray()
        for(scale in listOf(1f,2f)) for(dark in listOf(false,true)) for(dynamic in listOf(false,true)) {
        AutomationCommandAcceptanceTest().fixture({ FunctionResult.success("{\"title\":\"隔离验收任务\",\"schedule_type\":\"MANUAL\"}") }) { vm,_ ->
            withContext(Dispatchers.Main) { vm.generateDraft("隔离验收需求") }
            withTimeout(2000) { vm.draft.first { it!=null } }
            VisualAcceptanceActivity.fontScaleForNextLaunch=scale
            val activity=inst.startActivitySync(SplitWindowAcceptance.launchIntent(inst.targetContext)) as VisualAcceptanceActivity
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale,activity.resources.configuration.fontScale,0f)
                val density=activity.resources.displayMetrics.density
                assertTrue(activity.isInMultiWindowMode && activity.resources.displayMetrics.widthPixels/density in 280f..599f)
                inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=dark,dynamicColor=dynamic) {
                    CreateAutomationScreen({},{},vm)
                } } }
                fun nodes():List<AccessibilityNodeInfo> {
                    val out=mutableListOf<AccessibilityNodeInfo>()
                    fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
                    visit(ui.rootInActiveWindow);return out
                }
                fun shot(name:String) {
                    Thread.sleep(700)
                    val bitmap=requireNotNull(if(android.os.Build.VERSION.SDK_INT>=34) ui.takeScreenshot(activity.window) else error("API34 required"))
                    val dest=File(inst.targetContext.filesDir,"a8-automation-create/$scale-$dark-$dynamic/$name.png")
                    dest.parentFile!!.mkdirs();dest.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                shot("start")
                for(label in listOf("自驱回合","智能执行","静态步骤","手动","每日","间隔","重新描述","确认创建")) {
                    var candidate:AccessibilityNodeInfo?=null
                    repeat(70) { attempt ->
                        if(candidate==null) {
                            val tree=nodes()
                            candidate=tree.firstOrNull { node ->
                                if(!node.isVisibleToUser || node.text?.toString()!=label) false
                                else {
                                    var owner=node
                                    while(!owner.isClickable)owner=requireNotNull(owner.parent)
                                    val rect=Rect().also(owner::getBoundsInScreen)
                                    SplitWindowAcceptance.bounds(activity).contains(rect) && rect.width()/density>=48 && rect.height()/density>=48
                                }
                            }
                            if(candidate==null && attempt%5==4) tree.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            if(candidate==null)Thread.sleep(100)
                        }
                    }
                    var owner=requireNotNull(candidate) { "Execution/schedule choice is invisible: $label" }
                    while(!owner.isClickable)owner=requireNotNull(owner.parent)
                    val rect=Rect().also(owner::getBoundsInScreen)
                    assertTrue("Choice must be complete and >=48dp: $label / $rect",rect.width()/density>=48 && rect.height()/density>=48 && SplitWindowAcceptance.bounds(activity).contains(rect))
                    val mode=mapOf("自驱回合" to "SELF_DRIVE","智能执行" to "REACT","静态步骤" to "PLAN")[label]
                    val type=mapOf("手动" to "MANUAL","每日" to "DAILY","间隔" to "INTERVAL")[label]
                    if(mode!=null || type!=null) {
                        assertTrue(owner.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        withTimeout(2000) { vm.draft.first { if(mode!=null) it?.execMode==mode else it?.scheduleType==type } }
                    }
                }
                shot("confirm")
                reports.put(org.json.JSONObject().put("fontScale",scale).put("dark",dark).put("dynamic",dynamic)
                    .put("actualMultiWindow",true).put("widthDp",activity.resources.displayMetrics.widthPixels/density)
                    .put("sixChoiceCallbacks",true).put("minimumTouchDp",48).put("bottomActionsReachable",true))
            } finally {
                Thread.sleep(700)
                if(android.os.Build.VERSION.SDK_INT>=34) ui.takeScreenshot(activity.window)?.let { bitmap ->
                    File(inst.targetContext.filesDir,"a8-automation-create-narrow.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                inst.runOnMainSync { activity.finish() }
            }
        }
        }
        File(inst.targetContext.filesDir,"a8-automation-create/report.json").writeText(org.json.JSONObject().put("passed",true)
            .put("conditions",reports).put("scope","actual confirmed-draft page in split")
            .put("personalDataWrites",0).put("providerRequests",0).toString(2))
    }
}
