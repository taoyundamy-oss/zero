package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.automation.AutomationDetailDialog
import com.zero.v2.app.ui.automation.AutomationUiState
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.data.entity.AutomationEntity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production detail renderer, long synthetic results, real split; no task execution. */
class AutomationDetailVisualAcceptanceTest {
    @Test fun longResultsAndActionsRemainReadableInActualSplit() {
        val inst=InstrumentationRegistry.getInstrumentation();val ui=inst.uiAutomation
        val reports=org.json.JSONArray()
        for(scale in listOf(1f,2f)) for(dark in listOf(false,true)) for(dynamic in listOf(false,true)) {
            VisualAcceptanceActivity.fontScaleForNextLaunch=scale
            val activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale,activity.resources.configuration.fontScale,0f)
                assertTrue(activity.isInMultiWindowMode)
                val density=activity.resources.displayMetrics.density
                val width=activity.resources.displayMetrics.widthPixels/density
                assertTrue(width in 280f..599f)
                val mode=if(dark) "SELF_DRIVE" else "PLAN"
                val task=AutomationEntity("visual", "隔离长标题：完整阅读并保留操作入口", "隔离描述", "{}",execMode=mode,status=if(dynamic) "PAUSED" else "ACTIVE")
                val steps=(1..24).joinToString("\n") { "隔离步骤记录第${it}行：完整结果" }+"\n步骤末尾验收标记"
                val final=(1..24).joinToString("\n") { "隔离最终结果第${it}行：完整结果" }+"\n最终末尾验收标记"
                val finalContent=mutableStateOf<String?>(null)
                inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=dark,dynamicColor=dynamic) {
                    val c=MaterialTheme.colorScheme
                    fun contrast(a:androidx.compose.ui.graphics.Color,b:androidx.compose.ui.graphics.Color):Float {
                        val x=a.luminance();val y=b.luminance();return (maxOf(x,y)+0.05f)/(minOf(x,y)+0.05f)
                    }
                    assertTrue(contrast(c.onSurfaceVariant,c.surfaceVariant)>=4.5f)
                    assertTrue(contrast(c.onPrimaryContainer,c.primaryContainer)>=4.5f)
                    AutomationDetailDialog(task,AutomationUiState(),{},false,null,null,mapOf(1 to steps),finalContent.value,{},{},{},{})
                } } }
                fun tree():List<AccessibilityNodeInfo> {
                    val out=mutableListOf<AccessibilityNodeInfo>()
                    fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
                    visit(ui.rootInActiveWindow);return out
                }
                fun find(label:String,back:Boolean=false):AccessibilityNodeInfo {
                    repeat(80) { attempt ->
                        val nodes=tree()
                        nodes.firstOrNull { it.isVisibleToUser && (it.text?.toString()?.contains(label)==true || it.contentDescription?.toString()==label) }?.let { return it }
                        if(attempt%3==2) nodes.firstOrNull { it.isScrollable }?.performAction(if(back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        Thread.sleep(100)
                    }
                    error("Missing synthetic detail result/action: $label")
                }
                for(label in listOf("编辑",if(dynamic) "恢复" else "暂停","删除","关闭","立即执行")) {
                    var owner=find(label);while(!owner.isClickable)owner=requireNotNull(owner.parent)
                    val rect=Rect().also(owner::getBoundsInScreen)
                    assertTrue(rect.width()/density>=48 && rect.height()/density>=48)
                    val window=ui.windows.first { it.id==owner.windowId };val bounds=Rect().also(window::getBoundsInScreen)
                    assertTrue("Action must be inside its actual dialog: $label",bounds.contains(rect))
                }
                if(dark) find("自驱回合")
                var toggle=find("展开");while(!toggle.isClickable)toggle=requireNotNull(toggle.parent)
                assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                find("收起")
                fun findScrollable():AccessibilityNodeInfo {
                    repeat(50) { tree().firstOrNull { it.isScrollable }?.let { return it };Thread.sleep(100) }
                    error("Detail must have a scroll viewport: "+tree().map { "${it.className}:${it.text}:${it.isScrollable}" })
                }
                fun scrollEnd() {
                    repeat(70) {
                        val scroll=findScrollable()
                        if(scroll.actionList.none { it.id==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) return
                        assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));Thread.sleep(100)
                    }
                    error("Detail did not reach its actual scroll end")
                }
                fun shot(name:String) {
                    Thread.sleep(700)
                    val root=requireNotNull(ui.rootInActiveWindow);assertEquals("com.zero.v2",root.packageName.toString())
                    val bounds=Rect().also(ui.windows.first { it.id==root.windowId }::getBoundsInScreen)
                    assertTrue(activity.windowManager.currentWindowMetrics.bounds.contains(bounds))
                    val screen=requireNotNull(ui.takeScreenshot())
                    val crop=Bitmap.createBitmap(screen,bounds.left,bounds.top,bounds.width(),bounds.height())
                    try {
                        val dest=File(inst.targetContext.filesDir,"a8-automation-detail/$scale-$dark-$dynamic/$name.png")
                        dest.parentFile!!.mkdirs();dest.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG,100,it) }
                    } finally { crop.recycle();if(!screen.isRecycled) screen.recycle() }
                }
                scrollEnd();find("步骤末尾验收标记");shot("step-tail")
                inst.runOnMainSync { finalContent.value=final };Thread.sleep(200)
                scrollEnd();find("最终末尾验收标记");shot("final-tail")
                reports.put(org.json.JSONObject().put("fontScale",scale).put("dark",dark).put("dynamic",dynamic)
                    .put("widthDp",width).put("actualMultiWindow",true).put("longStepAndFinalTail",true)
                    .put("minimumTouchDp",48).put("pairedContrastAtLeast",4.5))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir,"a8-automation-detail/report.json").writeText(org.json.JSONObject().put("passed",true)
            .put("conditions",reports).put("scope","production detail renderer with synthetic results")
            .put("personalDataWrites",0).put("providerRequests",0).toString(2))
    }
}
