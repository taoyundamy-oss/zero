package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.ProfileEditorPage
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.services.llm.LlmProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production editor with isolated callbacks: invalid text must never save an old fallback value. */
class ProfileEditorAcceptanceTest {
    @Test fun userTokenPricesSaveThroughProductionEditorWithoutChangingModelIdentity() {
        for (dark in listOf(false, true)) {
            val seed = LlmProfile.localQwen("http://127.0.0.1:8080/v1", "isolated")
                .copy(tokenPrices = com.zero.v2.services.llm.ModelTokenPrices("2.31", "0.12", "5.67", "1.8"))
            val saved = java.util.concurrent.CopyOnWriteArrayList<LlmProfile>()
            val activity = launch({ saved.add(it) }, {}, dark, false, seed = seed)
            try {
                val field = find { it.isEditable && it.text?.toString() == "2.31" }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "2.32")
                }))
                val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
                val file = java.io.File(inst.targetContext.filesDir, "infrastructure-price-$dark.png")
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                click("保存全部")
                assertEquals(seed.copy(tokenPrices = seed.tokenPrices.copy(input = "2.32")), saved.single())
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }

    @Test fun modernResponsesControlsAndModelCardKeepConfigurationSemantics() {
        for (dark in listOf(false, true)) {
            val saved = java.util.concurrent.CopyOnWriteArrayList<LlmProfile>()
            val seed = LlmProfile("visual-luna", "GPT-6 Luna",
                "https://api.cloudflare.com/client/v4/accounts/example/ai/v1", "isolated-ref",
                modelId = "openai/gpt-6-luna", gatewayMode = com.zero.v2.services.llm.GatewayMode.CF_GATEWAY,
                cloudflareGatewayId = "default", contractId = "cf-luna-responses-v1",
                parameters = mapOf("effort" to "low"),
                capabilities = setOf(com.zero.v2.services.llm.ModelCapability.CHAT,
                    com.zero.v2.services.llm.ModelCapability.FUNCTION_CALLING))
            val activity = launch({ saved.add(it) }, {}, dark, false, seed = seed)
            fun shot(name: String) {
                Thread.sleep(250)
                val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
                val file = java.io.File(inst.targetContext.filesDir, "llm-modern-ui/$dark/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            try {
                find { it.text?.toString() == "模型身份" }
                shot("identity")
                click("medium")
                shot("reasoning")
                click("图片输入")
                shot("capabilities")
                click("保存全部")
                assertEquals("medium", saved.single().parameters["effort"])
                assertTrue(com.zero.v2.services.llm.ModelCapability.IMAGE_INPUT in saved.single().capabilities)
                assertEquals(seed.apiKeyRef, saved.single().apiKeyRef)
                var copied = false
                inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = false) {
                    EditorSurface {
                        com.zero.v2.app.ui.settings.ProfileCard(seed, true, {}, {}, {}, {}, { copied = true })
                    }
                } } }
                find { it.text?.toString() == "编辑模型" }
                shot("model-card")
                click("更多")
                click("复制模型（共享连接）")
                assertTrue(copied)
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }
    @Test fun providerTemplateCreatesIndependentCredentialReferenceAndRestores() {
        var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .putExtra("acceptancePage", "profile-connections")
            .putExtra("acceptanceWidth", 420)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            click("从提供方模板创建独立连接")
            click("DeepSeek")
            find { it.isEditable && it.text?.toString() == "https://api.deepseek.com" }
            val monitor = inst.addMonitor(VisualAcceptanceActivity::class.java.name, null, false)
            try {
                inst.runOnMainSync { activity.recreate() }
                activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10_000)) as VisualAcceptanceActivity
            } finally { inst.removeMonitor(monitor) }
            find { it.isEditable && it.text?.toString() == "https://api.deepseek.com" }
            click("保存全部")
            assertTrue(activity.lastNavigation.orEmpty().startsWith("saved:connection:"))
            assertTrue(activity.lastNavigation.orEmpty().contains(":llm_connection:"))
        } finally { inst.runOnMainSync { activity.finish() } }
    }
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null || !n.refresh()) return
            result.add(n)
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(100) { attempt ->
            nodes().firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            if (attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(60)
        }
        error("Editor acceptance control missing")
    }
    @Test fun activityRecreationRestoresDraftWithoutRestoringUnsavedKey() {
        var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .putExtra("acceptancePage", "profile-editor")
            .putExtra("acceptanceWidth", 1200)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            assertTrue(find { it.isEditable && it.text.isNullOrEmpty() }.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "isolated-unsaved-key")
                }))
            setReply("1024")
            val monitor = inst.addMonitor(VisualAcceptanceActivity::class.java.name, null, false)
            try {
                inst.runOnMainSync { activity.recreate() }
                activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10_000)) as VisualAcceptanceActivity
            } finally { inst.removeMonitor(monitor) }
            find { it.isEditable && it.text?.toString() == "1024" }
            // Search from the top after the reply field scrolled the editor down.
            repeat(10) { nodes().firstOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(60) }
            find { it.isEditable && it.text.isNullOrEmpty() }
            assertTrue(nodes().none { it.isEditable && it.text?.toString() == "isolated-unsaved-key" })
            click("保存全部")
            assertEquals("saved-1024", activity.lastNavigation)
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun invalidReplyLimitCannotSaveOrChangeCredentials() {
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val saved = java.util.concurrent.CopyOnWriteArrayList<LlmProfile>()
        val keys = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            inst.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    ZeroTheme {
                        EditorSurface {
                            ProfileEditorPage(LlmProfile.localQwen("http://127.0.0.1:8080/v1", "isolated"),
                                { "未配置" }, { keys.add(it) }, { saved.add(it) }, {})
                        }
                    }
                }
            }
            assertTrue(find { it.isEditable && it.text.isNullOrEmpty() }.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "isolated-test-key")
                }))
            val input = find { it.isEditable && it.text?.toString() == "2048" }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "invalid")
            }))
            var save = find { it.text?.toString() == "保存全部" }
            while (!save.isClickable && save.parent != null) save = save.parent
            assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(200)
            assertEquals("Invalid numeric draft must be rejected before callbacks", 0, saved.size)
            assertEquals(0, keys.size)
            find { it.text?.toString()?.contains("回复上限") == true && it.text?.toString()?.contains("整数") == true }
        } finally { inst.runOnMainSync { activity.finish() } }
    }
    private fun click(text: String) {
        var node = find { it.text?.toString() == text }
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(200)
    }

    private fun launch(onSave: (LlmProfile) -> Unit, onBack: () -> Unit, dark: Boolean = false, dynamic: Boolean = true,
        backReady: ((() -> Unit)?) -> Unit = {},
        seed: LlmProfile = LlmProfile.localQwen("http://127.0.0.1:8080/v1", "isolated")): VisualAcceptanceActivity {
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        inst.runOnMainSync {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.setContent {
                ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                    EditorSurface {
                        ProfileEditorPage(seed,
                            { "未配置" }, {}, onSave, onBack, backReady)
                    }
                }
            }
        }
        return activity
    }

    private fun setReply(value: String) {
        assertTrue(find { it.isEditable && it.text?.toString() == "2048" }
            .performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
    }

    @Test fun longProfileFieldsSaveWithoutTruncationAtLargeResourceFont() {
        val seed = LlmProfile.localQwen("http://127.0.0.1:8080/" + "long-endpoint/".repeat(24) + "v1",
            "qwen-local-" + "long-model-".repeat(20)).copy(displayName = "本地模型长名称验收".repeat(16))
        try {
            for (dark in listOf(false, true)) for (profile in listOf(seed,
                LlmProfile.defaults().first { it.kind == LlmProfile.Kind.TTS }.copy(
                    displayName = seed.displayName, baseUrl = seed.baseUrl, modelId = seed.modelId,
                    voice = "long-voice-".repeat(18)))) {
                VisualAcceptanceActivity.fontScaleForNextLaunch = 2f
                val saved = java.util.concurrent.CopyOnWriteArrayList<LlmProfile>()
                val activity = launch({ saved.add(it) }, {}, dark, false, seed = profile)
                try {
                    assertEquals(2f, activity.resources.configuration.fontScale, .01f)
                    find { it.isEditable && it.text?.toString() == profile.displayName }
                    find { it.isEditable && it.text?.toString() == profile.modelId }
                    find { it.isEditable && it.text?.toString() == profile.baseUrl }
                    if (profile.kind == LlmProfile.Kind.TTS) find { it.isEditable && it.text?.toString() == profile.voice }
                    click("保存全部")
                    assertEquals(1, saved.size)
                    assertEquals(profile.displayName, saved.single().displayName)
                    assertEquals(profile.modelId, saved.single().modelId)
                    assertEquals(profile.baseUrl, saved.single().baseUrl)
                    assertEquals(profile.voice, saved.single().voice)
                    assertEquals(profile.apiKeyRef, saved.single().apiKeyRef)
                } finally { inst.runOnMainSync { activity.finish() } }
            }
        } finally { VisualAcceptanceActivity.fontScaleForNextLaunch = null }
    }

    @Test fun failedSaveRetainsDraftAndCanRetry() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val saved = java.util.concurrent.CopyOnWriteArrayList<LlmProfile>()
        val activity = launch({
            if (attempts.incrementAndGet() == 1) error("isolated rejection")
            saved.add(it)
        }, {})
        try {
            setReply("1024")
            click("保存全部")
            assertEquals(0, saved.size)
            find { it.text?.toString()?.contains("模型保存失败") == true }
            click("保存全部")
            assertEquals(1, saved.size)
            assertEquals(1024, saved.single().maxTokens)
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun dirtyCancelRequiresConfirmation() {
        val backs = java.util.concurrent.atomic.AtomicInteger()
        val activity = launch({}, { backs.incrementAndGet() })
        try {
            setReply("1024")
            click("取消")
            assertEquals(0, backs.get())
            click("继续编辑")
            assertEquals(0, backs.get())
            click("取消")
            click("放弃修改")
            assertEquals(1, backs.get())
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun editorResourceFontsAndThemesHaveReachableFooter() {
        val rows = org.json.JSONArray()
        val split = InstrumentationRegistry.getArguments().getString("profileWindow") == "split"
        val folderRoot = if (split) "a8-profile-editor-split" else "a8-profile-editor"
        try {
            for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
                VisualAcceptanceActivity.fontScaleForNextLaunch = scale
                val saved = java.util.concurrent.CopyOnWriteArrayList<LlmProfile>()
                val activity = launch({ saved.add(it) }, {}, dark, dynamic)
                try {
                    if (split) SplitWindowAcceptance.ensure(activity)
                    assertEquals(scale, activity.resources.configuration.fontScale, 0.01f)
                    val width = activity.resources.configuration.screenWidthDp
                    assertTrue(if (split) activity.isInMultiWindowMode && width in 280..599 else width >= 600)
                    find { it.isEditable && it.text?.toString() == "isolated" }
                    val folder = java.io.File(inst.targetContext.filesDir, "$folderRoot/$scale-$dark-$dynamic")
                    folder.mkdirs()
                    fun shot(name: String) {
                        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot(activity.window))
                        java.io.File(folder, "$name.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    shot("interface")
                    click("保存全部")
                    assertEquals(1, saved.size)
                    if (split) SplitWindowAcceptance.ensure(activity)
                    find { it.text?.toString() == "取消" }
                    shot("footer")
                    rows.put(org.json.JSONObject().put("fontScale", scale).put("dark", dark).put("dynamic", dynamic)
                        .put("widthDp", activity.resources.configuration.screenWidthDp)
                        .put("heightDp", activity.resources.configuration.screenHeightDp).put("saved", true)
                        .put("multiWindow", activity.isInMultiWindowMode))
                } finally { inst.runOnMainSync { activity.finish() } }
            }
            java.io.File(inst.targetContext.filesDir, "$folderRoot/matrix.json").writeText(rows.toString(2))
        } finally { VisualAcceptanceActivity.fontScaleForNextLaunch = null }
    }

    @Test fun parentBackUsesLatestDraftAndUnregistersAfterExit() {
        val backs = java.util.concurrent.atomic.AtomicInteger()
        var handler: (() -> Unit)? = null
        val activity = launch({}, { backs.incrementAndGet() }, backReady = { handler = it })
        try {
            setReply("1024")
            inst.runOnMainSync { requireNotNull(handler).invoke() }
            assertEquals("Parent return must keep the editor while dirty", 0, backs.get())
            find { it.text?.toString() == "继续编辑" }
            click("放弃修改")
            assertEquals(1, backs.get())
        } finally { inst.runOnMainSync { activity.setContent {}; activity.finish() } }
        inst.waitForIdleSync()
        assertEquals(null, handler)
    }

    @Test fun sharedConnectionSelectionRestoresWithoutWritingCredentials() {
        for (width in listOf(420, 1200)) {
            var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .putExtra("acceptancePage", "profile-connections").putExtra("acceptanceWidth", width)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            try {
                click("选择已有连接")
                click("共享验收连接 · https://fixture.example/v1")
                find { it.isEditable && it.text?.toString() == "https://fixture.example/v1" }
                val monitor = inst.addMonitor(VisualAcceptanceActivity::class.java.name, null, false)
                try {
                    inst.runOnMainSync { activity.recreate() }
                    activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10_000)) as VisualAcceptanceActivity
                } finally { inst.removeMonitor(monitor) }
                find { it.isEditable && it.text?.toString() == "https://fixture.example/v1" }
                click("保存全部")
                assertEquals("saved:fixture-shared:fixture-ref", activity.lastNavigation)
            } finally { inst.runOnMainSync { activity.finish() } }
        }
    }

    @Composable private fun EditorSurface(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
        }
    }

}
