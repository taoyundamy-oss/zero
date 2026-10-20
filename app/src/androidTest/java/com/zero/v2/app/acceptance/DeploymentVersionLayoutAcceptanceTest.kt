package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DeploymentStatus
import com.zero.v2.app.ui.settings.DeploymentVersionContent
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.moduleapi.ReleaseVersionStatus
import com.zero.v2.core.moduleapi.StatusFact
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Actual version-history projection, actual 200% resources and split; synthetic journal only. */
class DeploymentVersionLayoutAcceptanceTest {
    @Test fun unknownLongHistoryStateMustNotRemoveVersionHashReadingArea() {
        val inst = InstrumentationRegistry.getInstrumentation()
        VisualAcceptanceActivity.fontScaleForNextLaunch = 2f
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            SplitWindowAcceptance.ensure(activity)
            assertEquals(2f, activity.resources.configuration.fontScale, 0f)
            val status = DeploymentStatus(loaded = true, freshness = StatusFact.LIVE,
                active = ReleaseVersionStatus("current", "a0c0ffee".repeat(8), "ACTIVE", 1),
                history = listOf(ReleaseVersionStatus("old", "e1c0ffee".repeat(8), "ISOLATED_UNKNOWN_LONG_RELEASE_STATE", 1)))
            inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    DeploymentVersionContent(status, {})
                }
            } } }
            fun nodes(): List<AccessibilityNodeInfo> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
                visit(inst.uiAutomation.rootInActiveWindow); return out
            }
            var hash: AccessibilityNodeInfo? = null
            repeat(50) {
                hash = nodes().firstOrNull { it.text?.toString() == "e1c0ffee" }
                if (hash == null) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(100) }
            }
            assertTrue("History status must still be present", nodes().any { it.text?.toString()?.contains("ISOLATED_UNKNOWN_LONG_RELEASE_STATE") == true })
            val image = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
            java.io.File(inst.targetContext.filesDir, "a8-deployment-red.png").outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
            val node = requireNotNull(hash) { "History version hash must remain in the UI" }
            val rect = Rect().also(node::getBoundsInScreen)
            assertTrue("History hash needs a visible reading area, actual=$rect", node.isVisibleToUser && rect.width() > 0 && rect.height() > 0)
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
