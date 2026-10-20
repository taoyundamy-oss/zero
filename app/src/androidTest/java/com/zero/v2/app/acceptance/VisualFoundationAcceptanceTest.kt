package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.navigation.AppDestinations
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real device component matrix, with no user settings or provider traffic. */
class VisualFoundationAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun errorTailExplicitRetryIsReadableAndDisablesDuringGeneration() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val cases = JSONArray()
        try {
            for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                instrumentation.runOnMainSync { activity.retryExecutions = 0; activity.fixture = VisualAcceptanceActivity.Fixture("retry", dark, false, scale, 360) }
                settle()
                val tree = awaitTree("retry") { it.any { node -> node.text?.toString() == "重新生成本轮" } }
                assertTrue(tree.any { label(it).contains("已完成的工具操作不会撤销") })
                var button: AccessibilityNodeInfo? = tree.first { it.text?.toString() == "重新生成本轮" }
                while (button != null && !button.isClickable) button = button.parent
                val bounds = Rect().also { button!!.getBoundsInScreen(it) }
                assertTrue(bounds.height() / activity.resources.displayMetrics.density >= 48)
                assertTrue(requireNotNull(button).isEnabled && button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                settle()
                assertEquals("explicit-retry", activity.lastNavigation)
                val disabled = awaitTree("retry-disabled") { current -> current.any { node ->
                    if (node.text?.toString() != "重新生成本轮") false else {
                        var clickable: AccessibilityNodeInfo? = node
                        while (clickable != null && !clickable.isClickable) {
                            clickable = clickable.parent
                            clickable?.refresh()
                        }
                        clickable != null && clickable.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                    }
                } }
                var owner: AccessibilityNodeInfo? = disabled.first { it.text?.toString() == "重新生成本轮" }
                while (owner != null && !owner.isClickable) {
                    owner = owner.parent
                    owner?.refresh()
                }
                assertFalse(requireNotNull(owner).actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                owner!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                settle()
                assertEquals("disabled retry must not invoke a second request", 1, activity.retryExecutions)
                screenshot("retry-$dark-$scale")
                cases.put(JSONObject().put("dark", dark).put("fontScale", scale).put("explicitAction", true).put("disabledAfterClick", true))
            }
            File(context.filesDir, "a7-visual/retry-report.json").writeText(JSONObject().put("cases", cases).put("scope", "production_error_status_in_debug_fixture").toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun markdownCopyHorizontalScrollAndTailRemainReachable() {
        org.junit.Assume.assumeTrue(com.zero.v2.app.BuildConfig.DEBUG)
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        var original: android.content.ClipData? = null
        instrumentation.runOnMainSync { original = clipboard.primaryClip }
        val cases = JSONArray()
        try {
            for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                instrumentation.runOnMainSync { activity.fixture = VisualAcceptanceActivity.Fixture("markdown", dark, false, scale, 360) }
                settle()
                val name = "markdown-${if (dark) "dark" else "light"}-${scale}x"
                val tree = awaitTree(name) { it.any { node -> node.text?.toString() == "复制" } }
                var copy: AccessibilityNodeInfo? = tree.first { it.text?.toString() == "复制" }
                while (copy != null && !copy.isClickable) copy = copy.parent
                assertTrue(requireNotNull(copy).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                settle()
                instrumentation.runOnMainSync {
                    assertEquals(VisualAcceptanceActivity.MARKDOWN_CODE, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                }
                val scheme = requireNotNull(activity.palette)
                assertTrue(contrast(scheme.onSurface, scheme.surfaceContainerHigh) >= 4.5)
                assertTrue(contrast(scheme.onSurface, scheme.surfaceContainerLow) >= 4.5)
                screenshot("$name-top")
                val code = nodes().first { it.text?.toString()?.contains("CODE_START_") == true }
                val codeRect = Rect().also { code.getBoundsInScreen(it) }
                assertTrue(codeRect.width() > 0)
                // Move the actual horizontal owner, not the enclosing vertical transcript.
                var horizontal: AccessibilityNodeInfo? = code
                while (horizontal != null && horizontal.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) horizontal = horizontal.parent
                assertTrue("code horizontal scroll unavailable", requireNotNull(horizontal).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                settle()
                screenshot("$name-code-scrolled")
                var reached = false
                for (step in 0..12) {
                    val current = nodes()
                    if (current.any { it.isVisibleToUser && it.text?.toString() == "MARKDOWN_END" }) { reached = true; break }
                    val scroll = current.firstOrNull { it.isScrollable && it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                    assertNotNull("markdown tail scroll missing", scroll)
                    val viewport = Rect().also { scroll!!.getBoundsInScreen(it) }
                    swipe(viewport); settle()
                }
                assertTrue("markdown tail hidden", reached)
                val table = nodes().first { it.text?.toString() == "已确认" }
                var tableScroll: AccessibilityNodeInfo? = table
                while (tableScroll != null && tableScroll.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) tableScroll = tableScroll.parent
                assertTrue("table horizontal scroll unavailable", requireNotNull(tableScroll).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                settle()
                assertTrue(nodes().any { it.text?.toString() == "TABLE_END" && it.isVisibleToUser })
                screenshot("$name-tail")
                cases.put(JSONObject().put("name", name).put("copyExact", true).put("codeScroll", true).put("tableScroll", true).put("tailReached", true))
            }
            File(context.filesDir, "a7-visual/markdown-report.json").writeText(JSONObject().put("cases", cases)
                .put("scope", "real_message_bubble_in_isolated_debug_fixture").put("providerRequests", 0).toString(2))
        } finally {
            instrumentation.runOnMainSync { if (original == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(original!!); activity.finish() }
        }
    }

    @Test fun actualRootNavigationPreservesAppearancePreferences() {
        org.junit.Assume.assumeTrue(com.zero.v2.app.BuildConfig.DEBUG)
        assertTrue(instrumentation is ProviderAcceptanceRunner)
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("zero_appearance", android.content.Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        automation.serviceInfo = automation.serviceInfo.apply {
            eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK
        }
        // This runner skips Application.onCreate. Run the actual startup exactly once for this method.
        instrumentation.runOnMainSync { (context.applicationContext as com.zero.v2.app.ZeroApplication).onCreate() }
        val activity = instrumentation.startActivitySync(Intent(context, com.zero.v2.app.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun waitLabel(term: String): List<AccessibilityNodeInfo> {
            repeat(50) {
                val tree = nodes()
                if (tree.any { label(it).contains(term) }) return tree
                Thread.sleep(200)
            }
            throw AssertionError("Actual navigation did not reach $term")
        }
        fun clickLabel(term: String, prefix: Boolean = false) {
            repeat(50) {
            for (candidate in nodes()) {
                val match = if (prefix) candidate.contentDescription?.startsWith(term) == true
                    else candidate.text?.toString() == term || candidate.contentDescription?.toString() == term
                if (!match) continue
                var node: AccessibilityNodeInfo? = candidate
                while (node != null && !node.isClickable) node = node.parent
                if (node != null && node.refresh() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { settle(); return }
            }
            Thread.sleep(100)
            }
            throw AssertionError("Actual click target missing: $term")
        }
        try {
            settle()
            clickLabel("设置")
            waitLabel("偏好")
            screenshot("actual-main-settings")
            clickLabel("外观，", prefix = true)
            waitLabel("明暗模式")
            screenshot("actual-main-appearance")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitLabel("偏好")
            clickLabel("资料")
            waitLabel("知识与工作文件")
            screenshot("actual-main-library")
            clickLabel("工作区")
            waitLabel("系统状态")
            waitLabel("自动化任务")
            screenshot("actual-main-workspace")
            clickLabel("设置")
            waitLabel("偏好")
            assertEquals(original, preferences.all)
            File(context.filesDir, "a7-visual/actual-navigation-report.json").writeText(JSONObject()
                .put("completed", true).put("actualApplicationStartup", true)
                .put("settings", true).put("appearanceAndBack", true).put("library", true).put("workspace", true)
                .put("appearancePreferencesPreserved", true).put("liveStarted", false)
                .put("scope", "actual_main_application_root_navigation_no_settings_changes")
                .put("modelRequestsFromUiActions", 0).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun eventCardsAndSharedSettingsFollowTheme() {
        org.junit.Assume.assumeTrue(com.zero.v2.app.BuildConfig.DEBUG)
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val cases = JSONArray()
        try {
            for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                instrumentation.runOnMainSync { activity.fixture = VisualAcceptanceActivity.Fixture("events", dark, dynamic, scale, 360) }
                settle()
                val scheme = requireNotNull(activity.palette)
                val ratios = JSONObject()
                for (source in listOf("QQ_PRIVATE", "SELF_DRIVE", "NOTIFICATION", "UNKNOWN")) {
                    val colors = com.zero.v2.app.ui.theme.systemEventColors(scheme, source)
                    for ((name, foreground) in listOf("label" to colors.content, "body" to colors.body, "meta" to colors.meta)) {
                        val ratio = contrast(foreground, colors.container)
                        assertTrue("$source/$name contrast $ratio", ratio >= 4.5)
                        ratios.put("$source/$name", ratio)
                    }
                }
                val tree = awaitTree("events") { it.any { node -> label(node).contains("只读事件资料") } }
                assertTrue(tree.any { label(it).contains("自驱动") })
                val name = "events-${if (dark) "dark" else "light"}-${if (dynamic) "dynamic" else "zero"}-${scale}x"
                screenshot(name)
                cases.put(JSONObject().put("name", name).put("contrasts", ratios))
            }
            File(context.filesDir, "a7-visual/events-report.json").writeText(JSONObject().put("completed", true)
                .put("cases", cases).put("scope", "real_event_cards_and_shared_toggle_in_debug_fixture")
                .put("providerRequests", 0).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun chatAllSourcesComposerAndTailRemainReachable() {
        org.junit.Assume.assumeTrue(com.zero.v2.app.BuildConfig.DEBUG)
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val cases = JSONArray()
        try {
            for (width in listOf(360, 720)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                instrumentation.runOnMainSync { activity.fixture = VisualAcceptanceActivity.Fixture("chat", dark, dynamic, scale, width) }
                settle()
                val name = "chat-$width-${if (dark) "dark" else "light"}-${if (dynamic) "dynamic" else "zero"}-${scale}x"
                val scheme = requireNotNull(activity.palette)
                assertTrue(contrast(scheme.onSurface, scheme.background) >= 4.5)
                assertTrue(contrast(scheme.onSurface, scheme.surfaceContainerHigh) >= 4.5)
                assertTrue(contrast(scheme.onSurfaceVariant, scheme.surfaceContainer) >= 4.5)
                screenshot("$name-top")
                var tree = nodes()
                val header = tree.first { label(it).contains("执行轨迹") }
                var toggle: AccessibilityNodeInfo? = header
                while (toggle != null && !toggle.isClickable) toggle = toggle.parent
                assertTrue(requireNotNull(toggle).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitTree("chat-trace-expanded") { it.any { node -> label(node).contains("核对已确认资料") } }
                screenshot("$name-expanded")
                assertTrue(toggle!!.refresh() && toggle!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                settle()
                val scroll = nodes().firstOrNull { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                val viewport = Rect(); scroll?.getBoundsInScreen(viewport)
                var reached = false
                for (step in 0..16) {
                    tree = nodes()
                    if (tree.any { label(it).contains("本轮未完成") && it.isVisibleToUser }) { reached = true; break }
                    assertNotNull("$name tail hidden without a scroll owner", scroll)
                    swipe(viewport)
                }
                assertTrue("Tail not reachable: $name", reached)
                screenshot("$name-bottom")
                for ((label, action) in listOf("添加图片、音频或视频（5MB以内）" to "attach", "打开 Live 语音" to "live", "发送" to "send")) {
                    var target: AccessibilityNodeInfo? = nodes().first { it.contentDescription?.toString() == label }
                    while (target != null && !target.isClickable) target = target.parent
                    val button = requireNotNull(target)
                    val bounds = Rect(); button.getBoundsInScreen(bounds)
                    val minimum = (48 * activity.resources.displayMetrics.density).toInt()
                    assertTrue("$name $label clickable bounds=$bounds minimum=$minimum", bounds.width() >= minimum && bounds.height() >= minimum)
                    assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    settle(); assertEquals(action, activity.lastNavigation)
                }
                cases.put(JSONObject().put("name", name).put("tailReached", true).put("traceExpanded", true)
                    .put("composerTargetsAndCallbacks", true))
            }
            File(context.filesDir, "a7-visual/chat-report.json").writeText(JSONObject().put("completed", true)
                .put("cases", cases).put("allAssistantSources", com.zero.v2.core.common.MessageSource.entries.size)
                .put("scope", "real_chat_components_no_personal_data_or_model_requests")
                .put("imeAndMediaBusinessNotTested", true).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun stopButtonCancelsOwnedCollectorAndEnablesNextSend() {
        org.junit.Assume.assumeTrue(com.zero.v2.app.BuildConfig.DEBUG)
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            instrumentation.runOnMainSync { activity.fixture = VisualAcceptanceActivity.Fixture("stop") }
            val tree = awaitTree("stop-button") { it.any { node -> label(node).contains("停止回复") } }
            var button: AccessibilityNodeInfo? = tree.first { label(it).contains("停止回复") }
            while (button != null && !button.isClickable) button = button.parent
            assertTrue(requireNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitTree("stopped") { it.any { node -> label(node).contains("已停止回复") } && it.any { node -> node.contentDescription?.toString() == "发送" } }
            screenshot("a8a2-stopped")
            var send: AccessibilityNodeInfo? = nodes().first { it.contentDescription?.toString() == "发送" }
            while (send != null && !send.isClickable) send = send.parent
            assertTrue(requireNotNull(send).isEnabled && send!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            settle(); assertEquals("send-after-stop", activity.lastNavigation)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun visit(node: AccessibilityNodeInfo?, output: MutableList<AccessibilityNodeInfo>) {
        if (node == null || !node.refresh()) return
        output.add(node)
        for (index in 0 until node.childCount) visit(node.getChild(index), output)
    }

    private fun nodes(): List<AccessibilityNodeInfo> = mutableListOf<AccessibilityNodeInfo>().also {
        visit(automation.rootInActiveWindow, it)
    }

    private fun awaitTree(name: String, predicate: (List<AccessibilityNodeInfo>) -> Boolean): List<AccessibilityNodeInfo> {
        repeat(50) {
            val tree = nodes()
            if (predicate(tree)) return tree
            Thread.sleep(100)
        }
        File(instrumentation.targetContext.filesDir, "a7-visual/failed-tree-$name.json")
            .writeText(JSONArray(nodes().map { JSONObject().put("label", label(it))
                .put("clickable", it.isClickable).put("actions", JSONArray(it.actionList.map { action -> action.id })) }).toString(2))
        throw AssertionError("Accessibility tree did not reach $name")
    }

    private fun label(node: AccessibilityNodeInfo): String = "${node.text ?: ""} ${node.contentDescription ?: ""}"

    private fun settle() {
        instrumentation.waitForIdleSync()
        automation.waitForIdle(250, 5_000)
        Thread.sleep(150) // Compose exposes its virtual accessibility tree after the service connects.
    }

    private fun screenshot(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, "a7-visual/$name.png").also {
            it.parentFile!!.mkdirs()
        }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    private fun swipe(viewport: Rect) {
        val down = android.os.SystemClock.uptimeMillis()
        val x = viewport.centerX().toFloat()
        val start = viewport.top + viewport.height() * .75f
        val end = viewport.top + viewport.height() * .35f
        fun event(action: Int, y: Float) {
            val motion = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(motion, true))
            motion.recycle()
        }
        event(android.view.MotionEvent.ACTION_DOWN, start)
        for (step in 1..16) {
            Thread.sleep(20)
            event(android.view.MotionEvent.ACTION_MOVE, start + (end - start) * step / 16)
        }
        Thread.sleep(100) // End without a fling, preserving overlapping inspection regions.
        event(android.view.MotionEvent.ACTION_UP, end)
    }

    @Test fun settingsThemesLargeTextAndTraceAreReadableAndReachable() {
        org.junit.Assume.assumeTrue(com.zero.v2.app.BuildConfig.DEBUG)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            eventTypes = android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val cases = JSONArray()
        try {
            for (width in listOf(360, 720)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) for (font in listOf(1f, 2f)) {
                val name = "${width}dp-${if (dark) "dark" else "light"}-${if (dynamic) "dynamic" else "zero"}-${font.toInt()}x"
                instrumentation.runOnMainSync {
                    activity.fixture = VisualAcceptanceActivity.Fixture(dark = dark, dynamic = dynamic,
                        fontScale = font, paneWidthDp = width)
                }
                settle()
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                while (nodes().none { label(it).contains("角色与表达") } && android.os.SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                File(instrumentation.targetContext.filesDir, "a7-visual/tree-$name.json").also { it.parentFile!!.mkdirs() }
                    .writeText(JSONArray(nodes().map { node -> JSONObject().put("label", label(node))
                        .put("clickable", node.isClickable).put("visible", node.isVisibleToUser)
                        .put("actions", JSONArray(node.actionList.map { it.id })) }).toString(2))
                val scheme = requireNotNull(activity.palette)
                val pairs = mapOf("body" to (scheme.onBackground to scheme.background),
                    "row" to (scheme.onSurface to scheme.surfaceContainer),
                    "rowDescription" to (scheme.onSurfaceVariant to scheme.surfaceContainer),
                    "traceBody" to (scheme.onSurface to scheme.surfaceContainerLow),
                    "traceMeta" to (scheme.onSurfaceVariant to scheme.surfaceContainerLow),
                    "tracePrimary" to (scheme.primary to scheme.surfaceContainerLow),
                    "traceSecondary" to (scheme.secondary to scheme.surfaceContainerLow),
                    "traceTertiary" to (scheme.tertiary to scheme.surfaceContainerLow),
                    "traceError" to (scheme.error to scheme.surfaceContainerLow))
                val ratios = JSONObject()
                pairs.forEach { (key, pair) ->
                    val ratio = contrast(pair.first, pair.second)
                    ratios.put(key, ratio)
                    assertTrue("$name $key contrast $ratio < 4.5", ratio >= 4.5)
                }
                screenshot("$name-settings-top")
                val visibleRoutes = mutableSetOf<String>()
                val clickedRoutes = mutableSetOf<String>()
                val measuredRoutes = mutableSetOf<String>()
                var gestureScrolls = 0
                val geometry = JSONArray()
                val density = activity.resources.displayMetrics.density
                for (step in 0 until 10) {
                    val current = nodes()
                    val scroll = current.firstOrNull { node -> node.actionList.any { action ->
                        action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                            action.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } }
                    val viewport = Rect(0, 0, activity.resources.displayMetrics.widthPixels,
                        activity.resources.displayMetrics.heightPixels)
                    scroll?.getBoundsInScreen(viewport)
                    val positions = JSONArray()
                    for (destination in AppDestinations.settings) {
                        val row = current.firstOrNull { it.isClickable && it.isVisibleToUser && label(it).contains(destination.title) }
                            ?: continue
                        visibleRoutes.add(destination.route)
                        val rect = Rect().also { row.getBoundsInScreen(it) }
                        positions.put(JSONObject().put("route", destination.route).put("rect", rect.toShortString()))
                        if (rect.top > viewport.top && rect.bottom < viewport.bottom) {
                            assertTrue("Small click target: ${destination.route}", rect.height() / density >= 48)
                            measuredRoutes.add(destination.route)
                            if (destination.route != "assistant_role" && clickedRoutes.add(destination.route)) {
                                assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                settle()
                                assertEquals(destination.route, activity.lastNavigation)
                            }
                        }
                    }
                    if (measuredRoutes.size == AppDestinations.settings.size) break
                    val freshScroll = nodes().firstOrNull { node -> node.actionList.any { action ->
                        action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
                    if (freshScroll != null) {
                        // Full-page accessibility scrolling can jump over a tall 200% row.
                        // Overlapping physical swipes allow every complete target to be measured.
                        swipe(viewport)
                        gestureScrolls++
                        settle()
                    }
                    geometry.put(JSONObject().put("viewport", viewport.toShortString()).put("rows", positions)
                        .put("freshScrollPresent", freshScroll != null).put("gestureScrolls", gestureScrolls))
                }
                File(instrumentation.targetContext.filesDir, "a7-visual/geometry-$name.json").writeText(geometry.toString(2))
                assertEquals(AppDestinations.settings.map { it.route }.toSet(), visibleRoutes)
                assertEquals(visibleRoutes, measuredRoutes)
                assertEquals(visibleRoutes - "assistant_role", clickedRoutes)
                screenshot("$name-settings-bottom")
                instrumentation.runOnMainSync {
                    activity.fixture = activity.fixture.copy(page = "trace")
                }
                settle()
                val traceTree = awaitTree("$name-trace") { tree -> tree.any {
                    it.isClickable && label(it).contains("运行记录") } }
                screenshot("$name-trace-expanded")
                val toggle = traceTree.first { it.isClickable && label(it).contains("运行记录") }
                assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                settle()
                val collapsed = awaitTree("$name-collapsed") { tree ->
                    tree.any { label(it).contains("运行记录") } && tree.none { label(it).contains("核对文件来源") } }
                assertFalse(collapsed.any { label(it).contains("核对文件来源") })
                screenshot("$name-trace-collapsed")
                cases.put(JSONObject().put("name", name).put("contrasts", ratios)
                    .put("visibleRoutes", JSONArray(visibleRoutes)).put("clickedRoutes", JSONArray(clickedRoutes))
                    .put("minTouchTargetsPassed", true).put("traceCollapsePassed", true)
                    .put("nativeGestureScrolls", gestureScrolls))
            }
            for (page in listOf("library", "workspace")) {
                instrumentation.runOnMainSync { activity.fixture = VisualAcceptanceActivity.Fixture(page) }
                settle()
                screenshot("light-zero-1x-$page")
            }
            File(instrumentation.targetContext.filesDir, "a7-visual/report.json").writeText(JSONObject()
                .put("completed", true).put("cases", cases).put("providerRequests", 0)
                .put("scope", "production_components_in_debug_fixture_not_business_navigation")
                .put("fontScaleScope", "CompositionLocalDensity_not_system_settings")
                .put("screenWidthPx", activity.resources.displayMetrics.widthPixels)
                .put("screenHeightPx", activity.resources.displayMetrics.heightPixels).toString(2))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
