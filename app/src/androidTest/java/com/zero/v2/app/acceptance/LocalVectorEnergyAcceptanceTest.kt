package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.embedding.LocalBgeProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Bounded unplugged ABBA observation. Does not change battery, screen or backend settings. */
class LocalVectorEnergyAcceptanceTest {
    @Test fun unpluggedIdleLoadLoadIdle() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val root = File(context.cacheDir, "p5-resource").apply { mkdirs() }
        val report = JSONObject().put("passed", false).put("cloudCalls", 0)
            .put("scope", "four-minute foreground ABBA observation, not long-term battery life")
        val samples = JSONArray()
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        fun sample(phase: String): JSONObject {
            val status = requireNotNull(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
            return JSONObject().put("phase", phase).put("elapsedMs", SystemClock.elapsedRealtime())
                .put("plugged", status.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
                .put("temperatureTenthsC", status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1))
                .put("level", status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                .put("chargeUah", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
                .put("currentUa", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                .put("energyNwh", battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER))
                .put("pssKb", Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss)
        }
        val activity = inst.startActivitySync(Intent(context, WindowAcceptanceAnchorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun show(message: String) = inst.runOnMainSync {
            activity.setContentView(TextView(activity).apply {
                text = message; textSize = 24f; gravity = android.view.Gravity.CENTER
            })
        }
        try {
            val scoped = object : ContextWrapper(context) { override fun getFilesDir() = File(context.cacheDir, "p7-bge") }
            val provider = LocalBgeProvider(scoped)
            assertTrue(provider.isAvailable())
            assertEquals(512, requireNotNull(provider.embed("合成预热：核对备份摘要。 ")).size)
            show("本地向量能耗对照：请拔下USB。\n保持此页，约5分钟。完成后再接回USB。")
            File(root, "energy-ready").writeText("ready")
            val waitUntil = SystemClock.elapsedRealtime() + 300_000
            while (sample("wait").getInt("plugged") != 0) {
                check(SystemClock.elapsedRealtime() < waitUntil) { "unplug_wait_timeout" }; delay(500)
            }
            delay(30_000)
            for (phase in listOf("idle-before", "load-first", "load-second", "idle-after")) {
                show("能耗对照进行中：$phase\n请保持离线缆、不要操作其他应用。")
                repeat(60) {
                    val tick = SystemClock.elapsedRealtime()
                    val row = sample(phase)
                    check(row.getInt("plugged") == 0) { "power_reconnected_during_measurement" }
                    check(row.getInt("level") >= 30 && row.getInt("temperatureTenthsC") in 0..420) {
                        "battery_or_thermal_guard"
                    }
                    if (phase.startsWith("load")) {
                        assertEquals(512, requireNotNull(provider.embed(
                            "合成验收：保留用户数据，核对备份摘要，网络恢复后继续完成任务。".repeat(12))).size)
                        row.put("inferenceMs", SystemClock.elapsedRealtime() - tick)
                    }
                    samples.put(row)
                    delay((1000 - (SystemClock.elapsedRealtime() - tick)).coerceAtLeast(0))
                }
            }
            samples.put(sample("end"))
            report.put("passed", true)
            show("对照采样完成，请重新连接USB。\n结果尚需分析，不代表长期能耗达标。")
            val reconnectUntil = SystemClock.elapsedRealtime() + 300_000
            while (sample("reconnect").getInt("plugged") == 0 && SystemClock.elapsedRealtime() < reconnectUntil) delay(500)
        } finally {
            File(root, "energy.json").writeText(report.put("samples", samples).toString(2))
            inst.runOnMainSync { activity.finish() }
        }
    }
}
