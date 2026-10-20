package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.automation.AutomationReadStatus
import com.zero.v2.app.ui.automation.AutomationUiState
import com.zero.v2.app.ui.theme.ZeroPageHeader
import com.zero.v2.app.ui.theme.ZeroTheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Only the production read-status component; full task/detail/create matrices remain separate. */
class AutomationReadVisualAcceptanceTest {
    @Test fun failureRetryIsReadableAndReachableInActualSplit() {
        val inst = InstrumentationRegistry.getInstrumentation(); val ui = inst.uiAutomation
        val report = org.json.JSONArray()
        fun find(label: String): AccessibilityNodeInfo {
            repeat(50) {
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo?) {
                    if(node == null || !node.refresh()) return
                    nodes.add(node); for(i in 0 until node.childCount) visit(node.getChild(i))
                }
                visit(ui.rootInActiveWindow)
                nodes.firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return it }
                Thread.sleep(100)
            }
            error("Missing synthetic read-state action: $label")
        }
        for(scale in listOf(1f,2f)) for(dark in listOf(false,true)) for(dynamic in listOf(false,true)) {
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                SplitWindowAcceptance.ensure(activity)
                assertEquals(scale, activity.resources.configuration.fontScale, 0f)
                val width = activity.resources.displayMetrics.widthPixels/activity.resources.displayMetrics.density
                assertTrue(activity.isInMultiWindowMode && width in 280f..599f)
                val state = mutableStateOf(AutomationUiState(readError="无法读取任务，请重试；已加载的任务仍保留"))
                val retries = AtomicInteger()
                inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=dark,dynamicColor=dynamic) {
                    Scaffold(topBar={ ZeroPageHeader("自动化任务", {}) }) { padding ->
                        Column(Modifier.padding(padding)) {
                            AutomationReadStatus(state.value) {
                                retries.incrementAndGet();state.value=AutomationUiState()
                            }
                            Text(if(state.value.readError==null) "读取成功" else "隔离旧任务仍保留")
                        }
                    }
                } } }
                find("无法读取任务，请重试；已加载的任务仍保留")
                find("隔离旧任务仍保留")
                var retry=find("重新读取")
                while(!retry.isClickable) retry=requireNotNull(retry.parent)
                val r=Rect().also(retry::getBoundsInScreen);val density=activity.resources.displayMetrics.density
                assertTrue(r.width()/density >= 48 && r.height()/density >= 48 && SplitWindowAcceptance.bounds(activity).contains(r))
                Thread.sleep(700)
                val bitmap=requireNotNull(if(android.os.Build.VERSION.SDK_INT>=34) ui.takeScreenshot(activity.window) else error("API34 required"))
                val dest=File(inst.targetContext.filesDir,"a8-automation-read/$scale-$dark-$dynamic.png")
                dest.parentFile!!.mkdirs();dest.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                assertTrue(retry.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                find("读取成功");assertEquals(1,retries.get())
                report.put(org.json.JSONObject().put("fontScale",scale).put("dark",dark).put("dynamic",dynamic)
                    .put("widthDp",width).put("actualMultiWindow",true).put("retryTouchDp",r.height()/density))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir,"a8-automation-read/report.json").writeText(org.json.JSONObject()
            .put("passed",true).put("conditions",report).put("scope","production read-status only")
            .put("personalDataWrites",0).put("providerRequests",0).toString(2))
    }
}
