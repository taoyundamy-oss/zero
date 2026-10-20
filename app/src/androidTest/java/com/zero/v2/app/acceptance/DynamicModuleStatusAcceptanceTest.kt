package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DynamicModuleStatus
import com.zero.v2.app.ui.settings.DynamicModulesUiState
import com.zero.v2.app.ui.theme.ZeroTheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production error projection only; synthetic data, actual resources/split, no runtime actions. */
class DynamicModuleStatusAcceptanceTest {
    @Test fun errorAndRetryRemainReadableInAllEightConditions() {
        val inst=InstrumentationRegistry.getInstrumentation();val ui=inst.uiAutomation
        val reports=org.json.JSONArray()
        for(scale in listOf(1f,2f)) for(dark in listOf(false,true)) for(dynamic in listOf(false,true)) {
            VisualAcceptanceActivity.fontScaleForNextLaunch=scale
            val activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale,activity.resources.configuration.fontScale,0f)
                val density=activity.resources.displayMetrics.density
                val width=activity.resources.displayMetrics.widthPixels/density
                assertTrue(activity.isInMultiWindowMode && width in 280f..599f)
                val state=mutableStateOf(DynamicModulesUiState(loading=false,
                    readError="隔离读取失败，已保留原列表，请重新读取",
                    actionError="配置已保存，但停止模块失败，新配置尚未生效"))
                val detail=mutableStateOf(false);var retries=0;var dismissals=0
                inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=dark,dynamicColor=dynamic) {
                    val c=MaterialTheme.colorScheme
                    val a=c.onErrorContainer.luminance();val b=c.errorContainer.luminance()
                    assertTrue((maxOf(a,b)+0.05f)/(minOf(a,b)+0.05f)>=4.5f)
                    Column(Modifier.fillMaxSize().background(c.background).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        DynamicModuleStatus(state.value,{ retries++ },{
                            dismissals++;state.value=state.value.copy(actionError=null,actionMessage=null)
                        },detail=detail.value)
                        Text("隔离旧数据仍保留",color=c.onSurface)
                    }
                } } }
                fun nodes():List<AccessibilityNodeInfo> {
                    val out=mutableListOf<AccessibilityNodeInfo>()
                    fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
                    visit(ui.rootInActiveWindow);return out
                }
                fun find(label:String):AccessibilityNodeInfo {
                    repeat(60) { attempt ->
                        nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()==label }?.let { return it }
                        if(attempt%4==3)nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        Thread.sleep(100)
                    }
                    error("Missing controlled dynamic status: $label")
                }
                fun click(label:String) {
                    var owner=find(label);while(!owner.isClickable)owner=requireNotNull(owner.parent)
                    val rect=Rect().also(owner::getBoundsInScreen)
                    assertTrue(rect.width()/density>=48 && rect.height()/density>=48)
                    assertTrue(SplitWindowAcceptance.bounds(activity).contains(rect))
                    assertTrue(owner.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(100)
                }
                fun shot(name:String) {
                    Thread.sleep(400)
                    val bitmap=requireNotNull(ui.takeScreenshot(activity.window))
                    val dest=File(inst.targetContext.filesDir,"a8-dynamic-status/$scale-$dark-$dynamic/$name.png")
                    dest.parentFile!!.mkdirs();dest.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                find("配置已保存，但停止模块失败，新配置尚未生效");shot("list-error")
                click("重新读取");click("关闭提示");assertEquals(1,retries);assertEquals(1,dismissals)
                inst.runOnMainSync {
                    detail.value=true
                    state.value=state.value.copy(readError=null,detailError="模块已不存在，请返回列表确认")
                }
                find("模块已不存在，请返回列表确认");shot("detail-error");click("重新读取");assertEquals(2,retries)
                reports.put(org.json.JSONObject().put("fontScale",scale).put("dark",dark).put("dynamic",dynamic)
                    .put("widthDp",width).put("actualMultiWindow",true).put("minimumTouchDp",48).put("errorContrastAtLeast",4.5))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir,"a8-dynamic-status/report.json").writeText(org.json.JSONObject().put("passed",true)
            .put("conditions",reports).put("scope","production error/retry component; not full module pages")
            .put("providerRequests",0).put("personalDataWrites",0).toString(2))
    }
}
