package com.zero.v2.app.acceptance

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.DeploymentStatusViewModel
import com.zero.v2.app.ui.settings.DeploymentVersionCard
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.moduleapi.StatusFact
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Production repository -> actual VM -> public card; read-only, no module/release commands. */
class DeploymentVersionBindingAcceptanceTest {
    @Test fun liveRepositoryFactsReachTheProductionCard() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val repository = EntryPointAccessors.fromApplication(inst.targetContext, ProviderAcceptanceEntryPoint::class.java).systemState()
        withTimeout(60_000) { while (repository.snapshot().termux.deployments.isEmpty()) delay(200) }
        val observations = repository.snapshot().termux.deployments
        val selected = observations.firstOrNull { it.active != null } ?: observations.first()
        assertEquals(StatusFact.LIVE, selected.freshness)
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { DeploymentStatusViewModel(repository).also { store.put("deployment", it) } }
        VisualAcceptanceActivity.fontScaleForNextLaunch = 2f
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            SplitWindowAcceptance.ensure(activity)
            assertEquals(2f, activity.resources.configuration.fontScale, 0f)
            inst.runOnMainSync { activity.setContent { ZeroTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    DeploymentVersionCard(selected.moduleId, vm)
                }
            } } }
            withTimeout(10_000) {
                while (!vm.state.value.loaded || vm.state.value.freshness != StatusFact.LIVE) delay(50)
            }
            val current = repository.snapshot().termux.deployments.first { it.moduleId == selected.moduleId }
            assertEquals(current.active, vm.state.value.active)
            assertEquals(current.recent, vm.state.value.history)
            fun nodes(): List<AccessibilityNodeInfo> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
                visit(inst.uiAutomation.rootInActiveWindow); return out
            }
            val wanted = current.active?.contentHash?.take(8)?.ifBlank { "版本摘要未提供" } ?: "暂无受管活动版本"
            var found = false
            repeat(50) { attempt ->
                if (nodes().any { it.isVisibleToUser && it.text?.toString() == wanted }) found = true
                if (!found) { if (attempt%3==2) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); Thread.sleep(100) }
            }
            assertTrue("Actual source version must be visible in public card", found)
            val image = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
            File(inst.targetContext.filesDir, "a8-deployment-binding.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            File(inst.targetContext.filesDir, "a8-deployment-binding.json").writeText(org.json.JSONObject()
                .put("passed", true).put("moduleId", selected.moduleId).put("source", "actual_system_state_repository_view_model_public_card")
                .put("freshness", vm.state.value.freshness.name).put("hasManagedActiveVersion", current.active != null)
                .put("fontScale", activity.resources.configuration.fontScale).put("providerRequests", 0).put("releaseActions", 0).toString(2))
        } finally { inst.runOnMainSync { activity.finish(); store.clear() } }
    }
}
