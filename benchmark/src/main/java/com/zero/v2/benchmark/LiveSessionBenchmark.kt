package com.zero.v2.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.Configurator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual production NavHost and media service, nondebuggable target. No text/whitelist writes. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class LiveSessionBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private fun clickControl(label:String) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null || !node.refresh()) return null
            if(node.isVisibleToUser && (node.contentDescription?.toString()==label || node.text?.toString()==label)) return node
            for(index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        repeat(50) {
            for(window in automation.windows) {
                if(!window.isActive && !window.isFocused) continue
                val root=window.root ?: continue
                if(root.packageName?.toString()!="com.zero.v2") continue
                var node=find(root) ?: continue
                while(!node.isClickable) node=node.parent ?: break
                if(node.isClickable && node.isEnabled && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            Thread.sleep(100)
        }
        error("Actual accessibility control absent: $label")
    }
    private fun backend(method:String,path:String):JSONObject {
        val connection=java.net.URL("http://127.0.0.1:7878$path").openConnection() as java.net.HttpURLConnection
        connection.requestMethod=method; connection.connectTimeout=3000; connection.readTimeout=10000
        try {
            if(method=="POST") {
                connection.doOutput=true; connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use { it.write("{}".toByteArray()) }
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun qqStopped():Boolean {
        val health=backend("GET","/health")
        check(health.getBoolean("alive"))
        val modules=health.getJSONArray("modules")
        val qq=(0 until modules.length()).map { modules.getJSONObject(it) }.firstOrNull { it.getString("id")=="qqbot" }
        // Successful stop also permits no runtime handle after supervisor reconstruction.
        return qq==null || qq.getString("state")=="STOPPED"
    }
    private fun awaitListening(device:androidx.test.uiautomator.UiDevice,phase:String) {
        if(device.wait(Until.hasObject(By.text("正在听你说")),60000)) return
        val directory=InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null)!!
        device.dumpWindowHierarchy(File(directory,"a9-$phase-not-listening.xml"))
        File(directory,"a9-$phase-not-listening.json").writeText(JSONObject()
            .put("foregroundPackage",device.currentPackageName)
            .put("services",device.executeShellCommand("dumpsys activity services com.zero.v2"))
            .put("windowPolicy",device.executeShellCommand("dumpsys window policy")).toString(2))
        error("Live did not reach listening in $phase; failure-time hierarchy and owners retained")
    }
    @Test fun realLiveControlsAndReturn() {
        // Live intentionally animates continuously; controller idle waits must not multiply every action by 10s.
        Configurator.getInstance().waitForIdleTimeout=0
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val service=automation.serviceInfo
        service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo=service
        val memory = JSONArray()
        rule.measureRepeated(packageName="com.zero.v2", metrics=listOf(FrameTimingMetric()), iterations=3,
            compilationMode = CompilationMode.Ignore(), startupMode = StartupMode.WARM,
            setupBlock = {
                device.pressHome(); startActivityAndWait()
                clickControl("打开 Live 语音")
                awaitListening(device,"setup")
                clickControl("结束 Live")
                check(device.wait(Until.hasObject(By.desc("打开 Live 语音")),5000))
                check(backend("POST","/module/qqbot/stop").optBoolean("ok"))
                check(qqStopped())
                device.pressHome()
                var normal=0; val deadline=android.os.SystemClock.elapsedRealtime()+240000
                while(normal<2 && android.os.SystemClock.elapsedRealtime()<deadline) {
                    device.wakeUp()
                    val thermal=device.executeShellCommand("dumpsys thermalservice")
                    val skin=Regex("mValue=([0-9.]+), mType=3, mName=SKIN").find(thermal)?.groupValues?.get(1)?.toDouble()
                    normal=if(thermal.contains("Thermal Status: 0") && skin != null && skin <= 34.0) normal+1 else 0
                    Thread.sleep(5000)
                }
                check(normal==2) { "Warm setup cannot reach two normal thermal readings" }
            }) {
            val thermal=JSONArray()
            fun normalThermal(phase:String) {
                val report=device.executeShellCommand("dumpsys thermalservice")
                val status=Regex("Thermal Status: (\\d+)").find(report)?.groupValues?.get(1)?.toInt()
                thermal.put(JSONObject().put("phase",phase).put("status",status)
                    .put("elapsedMs",android.os.SystemClock.elapsedRealtime()))
                check(status==0) { "Measured window thermal abnormal at $phase" }
            }
            normalThermal("begin")
            startActivityAndWait()
            val openLive=device.wait(Until.findObject(By.desc("打开 Live 语音")),30000)
            if(openLive==null) device.dumpWindowHierarchy(File(InstrumentationRegistry.getInstrumentation().context
                .getExternalFilesDir(null),"a9-missing-entry.xml"))
            requireNotNull(openLive) { "Live entry absent; hierarchy retained" }
            clickControl("打开 Live 语音")
            awaitListening(device,"measure")
            check(device.hasObject(By.text("对话转写将在这里显示"))) { "Ambient speech changed the frozen workload" }
            Thread.sleep(1500)
            repeat(3) { index ->
                val interrupt=device.wait(Until.findObject(By.desc("打断")),5000)
                if(interrupt==null) device.dumpWindowHierarchy(File(InstrumentationRegistry.getInstrumentation().context
                    .getExternalFilesDir(null),"a9-missing-controls.xml"))
                requireNotNull(interrupt) { "Live interrupt absent; hierarchy retained" }
                clickControl("打断"); Thread.sleep(400)
                clickControl("屏幕阅读白名单")
                clickControl("取消")
                normalThermal("controls-$index")
            }
            val info=device.executeShellCommand("dumpsys meminfo com.zero.v2")
            check(device.hasObject(By.text("对话转写将在这里显示"))) { "Ambient speech changed the measured workload" }
            val pss=Regex("TOTAL PSS:\\s+(\\d+)").find(info)?.groupValues?.get(1)?.toLong()
            check(pss != null) { "Missing actual target PSS" }
            clickControl("结束 Live")
            check(device.wait(Until.hasObject(By.desc("打开 Live 语音")),5000))
            Thread.sleep(500)
            check(!device.executeShellCommand("dumpsys activity services com.zero.v2.app.background.LiveSessionService")
                .contains("ServiceRecord{"))
            check(qqStopped()) { "QQ resumed during the frozen workload" }
            normalThermal("end")
            memory.put(JSONObject().put("iteration",iteration ?: JSONObject.NULL)
                .put("pssKiB",pss).put("thermal",thermal))
        }
        val inst=InstrumentationRegistry.getInstrumentation()
        val tag=InstrumentationRegistry.getArguments().getString("resourceTag") ?: "untagged"
        File(inst.context.getExternalFilesDir(null),"a9-resource-$tag.json").writeText(JSONObject()
            .put("samples",memory).put("realControlsEnded",true).toString(2))
    }
}
