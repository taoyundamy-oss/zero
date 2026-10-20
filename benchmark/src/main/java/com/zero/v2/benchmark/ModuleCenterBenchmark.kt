package com.zero.v2.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** P2-7b：Module Center 首测脚本；不触发真实 Termux 命令或网络请求。 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMacrobenchmarkApi::class)
class ModuleCenterBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun moduleCenterNavigationAndScroll() = benchmarkRule.measureRepeated(
        packageName = "com.zero.v2",
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        compilationMode = CompilationMode.Ignore(),
        setupBlock = {
            device.pressHome()
            device.executeShellCommand("am force-stop com.zero.v2")
        }
    ) {
        startActivityAndWait()
        checkNotNull(device.wait(Until.findObject(By.text("设置")), 10_000)) {
            "设置入口未出现，无法测量模块管理列表"
        }.click()
        var moduleEntry = device.findObject(By.text("模块管理"))
        repeat(8) {
            if (moduleEntry == null) {
                device.swipe(
                    device.displayWidth / 2, device.displayHeight * 3 / 4,
                    device.displayWidth / 2, device.displayHeight / 4, 300
                )
                moduleEntry = device.wait(Until.findObject(By.text("模块管理")), 1_000)
            }
        }
        checkNotNull(moduleEntry) {
            "模块管理入口未出现"
        }.click()
        checkNotNull(device.wait(Until.findObject(By.textStartsWith("模块管理 (")), 10_000)) {
            "模块管理页面未出现"
        }
        check(device.findObject(By.text("暂无已注册模块")) == null) {
            "模块列表为空，不能作为滚动性能基线"
        }
        device.swipe(
            device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 4, 300
        )
        device.swipe(
            device.displayWidth / 2, device.displayHeight / 4,
            device.displayWidth / 2, device.displayHeight * 3 / 4, 300
        )
    }
}
