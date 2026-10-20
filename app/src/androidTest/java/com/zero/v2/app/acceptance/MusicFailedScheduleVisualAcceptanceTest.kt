package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.music.MusicLibraryContent
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.data.entity.ScheduledPlaybackEntity
import com.zero.v2.core.moduleapi.ToolDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production failed-result UI on a real split window, with isolated failing cleanup DAO. */
class MusicFailedScheduleVisualAcceptanceTest {
    @Test fun failedResultAndCleanupFailureAreReadableInLargeSplit() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation(); val ui = inst.uiAutomation
        val dispatcher = object : ToolDispatcher {
            override suspend fun execute(name: String, arguments: Map<String,Any?>): FunctionResult = error("No tool calls expected")
            override suspend fun execute(toolCall: ToolCall): FunctionResult = error("No tool calls expected")
            override suspend fun executeBatch(toolCalls: List<ToolCall>): List<FunctionResult> = error("No tool calls expected")
        }
        fun nodes(): List<AccessibilityNodeInfo> {
            val out=mutableListOf<AccessibilityNodeInfo>()
            fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
            visit(ui.rootInActiveWindow);return out
        }
        fun find(label:String, backwards:Boolean=false):AccessibilityNodeInfo {
            repeat(50) { attempt ->
                val tree=nodes()
                tree.firstOrNull { it.isVisibleToUser && (it.text?.toString()?.contains(label)==true || it.contentDescription?.toString()==label) }?.let { return it }
                if(attempt%5==4)tree.firstOrNull { it.isScrollable }?.performAction(if(backwards)AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(100)
            }
            error("Missing synthetic failed-schedule action: $label")
        }
        for(dark in listOf(false,true)) {
            MusicStateAcceptanceTest().fixture(dispatcher) { model,_ ->
                VisualAcceptanceActivity.fontScaleForNextLaunch=2f
                val activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                try {
                    SplitWindowAcceptance.ensure(activity)
                    assertEquals(2f,activity.resources.configuration.fontScale,0f)
                    assertTrue(activity.isInMultiWindowMode)
                    val width=activity.resources.displayMetrics.widthPixels/activity.resources.displayMetrics.density
                    assertTrue(width in 280f..599f)
                    inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=dark,dynamicColor=false) {
                        val live by model.state.collectAsState()
                        MusicLibraryContent(live.copy(schedules=listOf(ScheduledPlaybackEntity("synthetic-failed",0,status="failed"))),{},model)
                    } } }
                    find("播放失败 · 查看运行记录")
                    var button=find("清除记录")
                    while(!button.isClickable)button=requireNotNull(button.parent)
                    val r=Rect().also(button::getBoundsInScreen)
                    val window=SplitWindowAcceptance.bounds(activity);val density=activity.resources.displayMetrics.density
                    assertTrue(r.width()/density>=48 && r.height()/density>=48 && window.contains(r))
                    assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    find("无法清除定时记录，请重试。", backwards=true)
                    find("播放失败 · 查看运行记录") // Failed cleanup must keep the record visible.
                    Thread.sleep(1000)
                    val bitmap=requireNotNull(if (android.os.Build.VERSION.SDK_INT >= 34) ui.takeScreenshot(activity.window)
                        else error("Window-only capture requires API 34+"))
                    val output=File(inst.targetContext.filesDir,"a8-music-failed-ui/$dark-2.0.png")
                    output.parentFile!!.mkdirs();output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                } finally { inst.runOnMainSync { activity.finish() } }
            }
        }
        File(inst.targetContext.filesDir,"a8-music-failed-ui/report.json").writeText(org.json.JSONObject()
            .put("passed",true).put("actualMultiWindow",true).put("resourceFontScale",2)
            .put("darkAndLight",true).put("failedResultVisible",true).put("cleanupFailureVisible",true)
            .put("personalDataWrites",0).put("providerRequests",0).toString(2))
    }
}
