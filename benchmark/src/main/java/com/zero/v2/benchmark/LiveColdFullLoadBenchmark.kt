package com.zero.v2.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Separate cold-start and all-enabled background gates; never stops QQ or writes module config. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class LiveColdFullLoadBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val expected = setOf("bili_downloader", "image_gen", "mimo_tts", "qqbot", "qzone_post",
        "searx_search", "vision_pro", "web_fetch")

    private fun allModules(): JSONArray {
        val connection = java.net.URL("http://127.0.0.1:7878/health").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 3000; connection.readTimeout = 10000
        val health = try { JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) }
        finally { connection.disconnect() }
        check(health.getBoolean("alive"))
        val rows = health.getJSONArray("modules")
        val active = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optString("state") == "ACTIVE" }.map { it.getString("id") }.toSet()
        check(active == expected) { "Frozen eight-module background changed: $active" }
        return JSONArray(active.sorted())
    }

    private fun click(label: String) {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.isVisibleToUser && (node.contentDescription?.toString() == label || node.text?.toString() == label)) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        repeat(100) {
            for (window in inst.uiAutomation.windows) {
                if (!window.isActive && !window.isFocused) continue
                val root = window.root ?: continue
                if (root.packageName?.toString() != "com.zero.v2") continue
                var node = find(root) ?: continue
                while (!node.isClickable) node = node.parent ?: break
                if (node.isClickable && node.isEnabled && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            Thread.sleep(100)
        }
        error("Actual control absent: $label")
    }

    private fun prepareAutomation() {
        Configurator.getInstance().waitForIdleTimeout = 0
        val service = inst.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        inst.uiAutomation.serviceInfo = service
    }

    private fun targetPids(device: androidx.test.uiautomator.UiDevice): List<String> {
        val uid = inst.context.packageManager.getApplicationInfo("com.zero.v2", 0).uid.toString()
        return device.executeShellCommand("ps -A -o UID,PID,NAME").lineSequence().map { it.trim().split(Regex("\\s+")) }
            .filter { it.size == 3 && it[0] == uid && it[2] == "com.zero.v2" }.map { it[1] }.toList()
    }

    private fun thermal(device: androidx.test.uiautomator.UiDevice, label: String): JSONObject {
        val report = device.executeShellCommand("dumpsys thermalservice")
        val status = Regex("Thermal Status: (\\d+)").find(report)?.groupValues?.get(1)?.toInt()
        val skin = Regex("mValue=([0-9.]+), mType=3, mName=SKIN").find(report)?.groupValues?.get(1)?.toDouble()
        check(status == 0) { "Invalid measured thermal state at $label: $status" }
        check(skin != null) { "Missing measured SKIN temperature at $label" }
        return JSONObject().put("phase", label).put("status", status).put("skin", skin)
            .put("elapsedMs", android.os.SystemClock.elapsedRealtime())
    }

    private fun cool(device: androidx.test.uiautomator.UiDevice) {
        val maxSkin = InstrumentationRegistry.getArguments().getString("setupSkinMax")?.toDouble() ?: 34.0
        check(maxSkin in 30.0..37.5) { "Setup temperature must have a bounded, frozen calibration" }
        val readings = JSONArray()
        val tag = InstrumentationRegistry.getArguments().getString("resourceTag") ?: "untagged"
        val reportFile = File(inst.context.getExternalFilesDir(null), "a9-setup-$tag-${android.os.SystemClock.elapsedRealtime()}.json")
        var normal = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + 600000
        device.sleep()
        while (normal < 2 && android.os.SystemClock.elapsedRealtime() < deadline) {
            val report = device.executeShellCommand("dumpsys thermalservice")
            val skin = Regex("mValue=([0-9.]+), mType=3, mName=SKIN").find(report)?.groupValues?.get(1)?.toDouble()
            val status = Regex("Thermal Status: (\\d+)").find(report)?.groupValues?.get(1)?.toInt()
            normal = if (status == 0 && skin != null && skin <= maxSkin) normal + 1 else 0
            readings.put(JSONObject().put("elapsedMs", android.os.SystemClock.elapsedRealtime())
                .put("status", status).put("skin", skin).put("consecutiveNormal", normal))
            reportFile.writeText(JSONObject().put("setupSkinMax", maxSkin).put("readings", readings).toString(2))
            Thread.sleep(5000)
        }
        check(normal == 2) { "Full background cannot reach frozen setup temperature" }
        unlockAfterCooling(device)
        allModules()
    }

    private fun unlockAfterCooling(device: androidx.test.uiautomator.UiDevice) {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        Thread.sleep(500)
        fun unlocked() = device.executeShellCommand("dumpsys window policy").contains("showing=false")
        if (unlocked()) return
        var entry = device.wait(Until.findObject(By.res("com.android.systemui", "pinEntry")), 3000)
        if (entry == null) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 20)
            entry = device.wait(Until.findObject(By.res("com.android.systemui", "pinEntry")), 3000)
        }
        check(entry != null) { "Unlock input unavailable; no credential entered" }
        val pin = File(inst.context.filesDir, "a9-resource-private-pin").readText().trim()
        check(pin.matches(Regex("[0-9]{4,12}"))) { "Authorized temporary credential missing" }
        entry.click()
        for (digit in pin) {
            val code = android.view.KeyEvent.KEYCODE_0 + (digit - '0')
            inst.uiAutomation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code), true)
            inst.uiAutomation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code), true)
        }
        if (!unlocked()) device.pressEnter()
        repeat(10) {
            if (unlocked()) return
            Thread.sleep(500)
        }
        error("Single authorized unlock attempt failed; no retry")
    }

    private fun save(scene: String, rows: JSONArray) {
        val tag = InstrumentationRegistry.getArguments().getString("resourceTag") ?: "untagged"
        File(inst.context.getExternalFilesDir(null), "a9-$scene-$tag.json").writeText(
            JSONObject().put("scene", scene).put("samples", rows).toString(2))
    }

    private fun liveWorkload(device: androidx.test.uiautomator.UiDevice, temperatures: JSONArray): JSONObject {
        click("打开 Live 语音")
        check(device.wait(Until.hasObject(By.text("正在听你说")), 60000))
        val readyAt = android.os.SystemClock.elapsedRealtime()
        check(device.hasObject(By.text("对话转写将在这里显示"))) { "Ambient speech changed the frozen scene" }
        Thread.sleep(1500)
        repeat(3) { index ->
            click("打断"); Thread.sleep(400); click("屏幕阅读白名单"); click("取消")
            temperatures.put(thermal(device, "controls-$index"))
        }
        val pid = targetPids(device).single()
        val memory = device.executeShellCommand("dumpsys meminfo $pid")
        val pss = requireNotNull(Regex("TOTAL PSS:\\s+(\\d+)").find(memory)?.groupValues?.get(1)?.toLong())
        check(device.hasObject(By.text("对话转写将在这里显示")))
        click("结束 Live"); check(device.wait(Until.hasObject(By.desc("打开 Live 语音")), 5000))
        Thread.sleep(500)
        check(!device.executeShellCommand("dumpsys activity services com.zero.v2.app.background.LiveSessionService").contains("ServiceRecord{"))
        return JSONObject().put("readyAtMs", readyAt).put("pssKiB", pss).put("pidAfter", pid)
    }

    @Test fun coldMainAndFirstLiveWithEightModules() {
        prepareAutomation()
        val rows = JSONArray()
        rule.measureRepeated("com.zero.v2", listOf(StartupTimingMetric(), FrameTimingMetric()),
            compilationMode = CompilationMode.Ignore(), startupMode = StartupMode.COLD, iterations = 3,
            setupBlock = { device.pressHome(); cool(device) }) {
            val temperatures = JSONArray().put(thermal(device, "begin"))
            val before = targetPids(device)
            check(before.isEmpty()) { "Android target process is not cold: $before" }
            val modulesBefore = allModules()
            val startedAt = android.os.SystemClock.elapsedRealtime()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.desc("打开 Live 语音")), 30000))
            val workload = liveWorkload(device, temperatures)
            temperatures.put(thermal(device, "end"))
            rows.put(workload.put("iteration", iteration ?: JSONObject.NULL).put("pidBefore", JSONArray(before))
                .put("firstLiveReadyMs", workload.getLong("readyAtMs") - startedAt).put("modulesBefore", modulesBefore)
                .put("modulesAfter", allModules()).put("thermal", temperatures))
        }
        save("cold", rows)
    }

    @Test fun warmLiveWithEightModules() {
        prepareAutomation()
        val rows = JSONArray()
        rule.measureRepeated("com.zero.v2", listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Ignore(), startupMode = StartupMode.WARM, iterations = 3,
            setupBlock = {
                device.pressHome(); startActivityAndWait(); click("打开 Live 语音")
                check(device.wait(Until.hasObject(By.text("正在听你说")), 60000))
                click("结束 Live"); check(device.wait(Until.hasObject(By.desc("打开 Live 语音")), 5000))
                device.pressHome(); cool(device)
            }) {
            val temperatures = JSONArray().put(thermal(device, "begin"))
            val modulesBefore = allModules()
            startActivityAndWait()
            val workload = liveWorkload(device, temperatures)
            temperatures.put(thermal(device, "end"))
            rows.put(workload.put("iteration", iteration ?: JSONObject.NULL)
                .put("modulesBefore", modulesBefore).put("modulesAfter", allModules()).put("thermal", temperatures))
        }
        save("full", rows)
    }
}
