package com.zero.v2.app.acceptance

import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleDetailAcceptanceTest {
    @Test fun missingModuleReachesRequestedDetailInsteadOfInfiniteInitialLoading() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val owner = entry.moduleDetailAcceptanceFactory().create()
        val viewModels = androidx.lifecycle.ViewModelStore().apply { put("detail", owner) }
        val id = "a8_missing_${System.currentTimeMillis()}"
        assertNull(entry.lifecycleManager().getById(id))
        try {
            repeat(2) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.load(id) }
                delay(500)
                assertEquals("Missing module must have a completed, identifiable detail result", id, owner.state.value.moduleId)
                assertNull(owner.state.value.module)
                assertFalse(owner.state.value.isLoading)
                assertNotNull(owner.state.value.loadError)
            }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() } }
    }
    @Test fun unavailableDetailRetryIsVisibleAtLargeResourceFont() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val retries = java.util.concurrent.atomic.AtomicInteger()
        try {
            for (dark in listOf(false, true)) {
                VisualAcceptanceActivity.fontScaleForNextLaunch = 2f
                val activity = inst.startActivitySync(android.content.Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                try {
                    inst.runOnMainSync {
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        activity.setContent {
                            com.zero.v2.app.ui.theme.ZeroTheme(darkTheme = dark, dynamicColor = false) {
                                androidx.compose.material3.Surface(
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground) {
                                    com.zero.v2.app.ui.module.ModuleDetailUnavailable("模块暂不可用，请返回列表或重试。") {
                                        retries.incrementAndGet()
                                    }
                                }
                            }
                        }
                    }
                    assertEquals(2f, activity.resources.configuration.fontScale, 0.01f)
                    fun find(n: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                        if (n == null || !n.refresh()) return null
                        if (n.isVisibleToUser && n.text?.toString() == "重试") return n
                        for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                        return null
                    }
                    var node: android.view.accessibility.AccessibilityNodeInfo? = null
                    repeat(50) { if (node == null) { node = find(inst.uiAutomation.rootInActiveWindow); Thread.sleep(60) } }
                    var button = requireNotNull(node)
                    while (!button.isClickable && button.parent != null) button = button.parent
                    val expected = retries.get() + 1
                    assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    inst.waitForIdleSync()
                    assertEquals(expected, retries.get())
                } finally { inst.runOnMainSync { activity.finish() } }
            }
        } finally { VisualAcceptanceActivity.fontScaleForNextLaunch = null }
    }

}
