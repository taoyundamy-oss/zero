package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.live.*
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.services.realtime.RealtimeState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Production Live renderer; no microphone, projection consent, service, model or personal whitelist calls. */
class LivePageAcceptanceTest {
    @Test fun evictedReadingAnchorFallsBackToFirstRetainedEntry() {
        val activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        fun entry(i:Int)=TranscriptEntry(true,"裁剪阅读-$i\n"+"这是合成转写段落，用于核验阅读位置。".repeat(5))
        val entries=mutableStateOf((0..49).map(::entry))
        try {
            inst.runOnMainSync { activity.setContent { ZeroTheme {
                LiveSessionContent(LiveUiState(transcript=entries.value),LivePageActions({}, {}, {}, {}, {}, {}),emptySet(),{emptyList()})
            } } }
            find(entries.value.first().text)
            repeat(4) { assertTrue(nodes().first { it.isVisibleToUser && it.isScrollable }
                .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); Thread.sleep(200) }
            fun first()=nodes().filter { it.isVisibleToUser && it.text?.startsWith("裁剪阅读-")==true }
                .minBy { android.graphics.Rect().also(it::getBoundsInScreen).top }.text.toString()
            val before=first()
            assertTrue("Fixture must evict the current reading item",before.substringBefore('\n').substringAfter('-').toInt()<20)
            inst.runOnMainSync { entries.value=entries.value.drop(20)+(50..69).map(::entry) }
            Thread.sleep(500); val after=first()
            File(inst.targetContext.filesDir,"a9-evicted-anchor.json").writeText(JSONObject()
                .put("before",before.substringBefore('\n')).put("after",after.substringBefore('\n')).toString(2))
            assertEquals("Evicted anchor must land at first retained entry, not skip its neighbours",entries.value.first().text,after)
        } finally { inst.runOnMainSync { activity.finish() } }
    }
    @Test fun largerFontAndImeDialogPreserveReadingEntry() {
        var activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val entries=(0..49).map { TranscriptEntry(true,"字号阅读-$it\n"+"阅读连续性验收段落。".repeat(8)) }
        fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme {
            LiveSessionContent(LiveUiState(transcript=entries),LivePageActions({}, {}, {}, {}, {}, {}),emptySet(),{emptyList()})
        } } } }
        try {
            mount(); find(entries.first().text)
            repeat(3) { assertTrue(nodes().first { it.isVisibleToUser && it.isScrollable }
                .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)); Thread.sleep(200) }
            val anchor=nodes().filter { it.isVisibleToUser && it.text?.startsWith("字号阅读-")==true }
                .minBy { android.graphics.Rect().also(it::getBoundsInScreen).top }.text.toString()
            val monitor=inst.addMonitor(VisualAcceptanceActivity::class.java.name,null,false)
            try {
                inst.runOnMainSync { VisualAcceptanceActivity.fontScaleForNextLaunch=2f; activity.recreate() }
                activity=requireNotNull(inst.waitForMonitorWithTimeout(monitor,10000)) as VisualAcceptanceActivity
            } finally { inst.removeMonitor(monitor) }
            mount(); find(anchor)
            assertEquals(2f,activity.resources.configuration.fontScale,0f)
            click("屏幕阅读白名单"); find("搜索应用")
            val accessibility=inst.uiAutomation.serviceInfo
            accessibility.flags=accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            inst.uiAutomation.serviceInfo=accessibility
            val editable=nodes().first { it.isVisibleToUser && it.isEditable }
            val rect=android.graphics.Rect().also(editable::getBoundsInScreen)
            val now=android.os.SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(now,now,action,rect.centerX().toFloat(),rect.centerY().toFloat(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { inst.uiAutomation.injectInputEvent(event,true) } finally { event.recycle() }
            }
            Thread.sleep(700)
            assertTrue("Actual IME required",activity.window.decorView.rootWindowInsets
                ?.isVisible(android.view.WindowInsets.Type.ime())==true || inst.uiAutomation.windows.any {
                it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(300); click("取消"); find(anchor)
            File(inst.targetContext.filesDir,"a9-font-ime-reading.json").writeText(JSONObject()
                .put("anchor",anchor.substringBefore('\n')).put("fontScale",2).put("actualIme",true)
                .put("restoredAfterDialog",true).toString(2))
        } finally { VisualAcceptanceActivity.fontScaleForNextLaunch=null; inst.runOnMainSync { activity.finish() } }
    }
    @Test fun stateColorUsesIntermediatePixelsAndReducedMotionSnaps() {
        val activity=inst.startActivitySync(Intent(inst.targetContext,VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val ui=mutableStateOf(LiveUiState(state=RealtimeState.LISTENING))
        val reduced=mutableStateOf(false)
        var frameIndex=0
        fun pixel():Int {
            val bounds=android.graphics.Rect().also(find("开始 Live")::getBoundsInScreen)
            val bitmap=requireNotNull(inst.uiAutomation.takeScreenshot())
            try {
                val crop=android.graphics.Bitmap.createBitmap(bitmap,bounds.left,bounds.top,bounds.width(),bounds.height())
                val frame=File(inst.targetContext.filesDir,"a9-color/frame-${frameIndex++}.png")
                frame.parentFile!!.mkdirs()
                try { frame.outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { crop.recycle() }
                return bitmap.getPixel(bounds.centerX(),bounds.bottom-24)
            } finally { bitmap.recycle() }
        }
        try {
            inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme=false,dynamicColor=false) {
                LiveSessionContent(ui.value,LivePageActions({}, {}, {}, {}, {}, {}),emptySet(),{emptyList()},
                    reduceMotion=reduced.value)
            } } }
            find("正在听你说"); Thread.sleep(300)
            val initial=pixel()
            inst.runOnMainSync { ui.value=ui.value.copy(state=RealtimeState.SPEAKING) }
            val frames=mutableListOf<Int>()
            repeat(5) { Thread.sleep(12); frames.add(pixel()) }
            Thread.sleep(300); val target=pixel()
            assertNotEquals("State palette must differ",initial,target)
            File(inst.targetContext.filesDir,"a9-color.json").writeText(JSONObject().put("initial",initial)
                .put("target",target).put("frames",JSONArray(frames)).toString(2))
            assertTrue("State transition needs intermediate pixels",frames.any { it!=initial && it!=target })
            inst.runOnMainSync { reduced.value=true; ui.value=ui.value.copy(state=RealtimeState.LISTENING) }
            Thread.sleep(300); val reducedInitial=pixel()
            inst.runOnMainSync { ui.value=ui.value.copy(state=RealtimeState.SPEAKING) }
            val snaps=mutableListOf<Int>()
            repeat(3) { Thread.sleep(20); snaps.add(pixel()) }
            assertTrue("Reduced motion must reach final state immediately",snaps.all { it==target })
            File(inst.targetContext.filesDir,"a9-color.json").writeText(JSONObject().put("initial",initial)
                .put("target",target).put("frames",JSONArray(frames)).put("reducedInitial",reducedInitial)
                .put("reducedFrames",JSONArray(snaps)).put("reducedSnaps",true).toString(2))
        } finally { inst.runOnMainSync { activity.finish() } }
    }
    @Test fun boundedTranscriptKeepsReadingAnchorWhenHeadIsTrimmed() {
        var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val entries = mutableStateOf((0..49).map { TranscriptEntry(true,
            "阅读锚点-$it\n" + "这是合成转写段落，用于核验阅读位置。".repeat(5), it.toLong()) })
        fun mount() {
            inst.runOnMainSync { activity.setContent { ZeroTheme {
                LiveSessionContent(LiveUiState(transcript = entries.value),
                    LivePageActions({}, {}, {}, {}, {}, {}), emptySet(), { emptyList() })
            } } }
        }
        try {
            mount()
            find(entries.value.first().text)
            repeat(4) {
                val scroll = nodes().first { it.isVisibleToUser && it.isScrollable }
                assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                Thread.sleep(200)
            }
            val anchor = nodes().filter { it.isVisibleToUser && it.text?.startsWith("阅读锚点-") == true }
                .sortedBy { android.graphics.Rect().also(it::getBoundsInScreen).top }.last()
            val text = anchor.text.toString()
            val before = android.graphics.Rect().also(anchor::getBoundsInScreen)
            inst.runOnMainSync { entries.value = entries.value.drop(3) + (50..52).map {
                TranscriptEntry(true, "阅读锚点-$it\n" + "新增合成转写。".repeat(8), it.toLong())
            } }
            Thread.sleep(500)
            val after = android.graphics.Rect().also(find(text)::getBoundsInScreen)
            File(inst.targetContext.filesDir, "a9-reading-anchor.json").writeText(JSONObject()
                .put("entry",text.substringBefore('\n')).put("before",before.toShortString())
                .put("after",after.toShortString()).put("trimmed",3).toString(2))
            assertTrue("Head trim must preserve the same reading entry position: $before -> $after",
                kotlin.math.abs(before.top-after.top) <= 2)
            val monitor = inst.addMonitor(VisualAcceptanceActivity::class.java.name, null, false)
            try {
                inst.runOnMainSync { activity.recreate() }
                activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10000)) as VisualAcceptanceActivity
            } finally { inst.removeMonitor(monitor) }
            mount(); Thread.sleep(500)
            val restored = android.graphics.Rect().also(find(text)::getBoundsInScreen)
            assertTrue("Same-task recreation retains reading offset", kotlin.math.abs(after.top-restored.top) <= 2)
            val tail = entries.value.last().text
            for (i in 0..35) {
                if (nodes().any { it.isVisibleToUser && it.text?.toString() == tail }) break
                assertTrue(nodes().first { it.isVisibleToUser && it.isScrollable }
                    .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                Thread.sleep(120)
            }
            find(tail)
            inst.runOnMainSync { entries.value = entries.value.drop(1) + TranscriptEntry(true,"新增末行转写",53) }
            find("新增末行转写")
            File(inst.targetContext.filesDir, "a9-reading-anchor.json").writeText(JSONObject()
                .put("entry",text.substringBefore('\n')).put("before",before.toShortString())
                .put("after",after.toShortString()).put("restored",restored.toShortString())
                .put("boundedTailFollow",true).put("trimmed",3).toString(2))
        } finally { inst.runOnMainSync { activity.finish() } }
    }
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || !node.refresh()) return
            result.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(inst.uiAutomation.rootInActiveWindow)
        return result
    }
    private fun find(label: String): AccessibilityNodeInfo {
        repeat(80) {
            nodes().firstOrNull { it.isVisibleToUser &&
                (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return it }
            Thread.sleep(80)
        }
        error("Missing Live control: $label")
    }
    private fun click(label: String) {
        var node = find(label)
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(150)
    }
    private fun shot(name: String) {
        val image = requireNotNull(inst.uiAutomation.takeScreenshot())
        val file = File(inst.targetContext.filesDir, "a9-live/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun actualImeKeepsWhitelistConfirmationFullyVisible() {
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            inst.runOnMainSync { activity.setContent { ZeroTheme {
                LiveSessionContent(LiveUiState(), LivePageActions({}, {}, {}, {}, {}, {}), emptySet(),
                    { listOf(AppEntry("fixture.reader", "验收阅读器")) })
            } } }
            click("屏幕阅读白名单"); find("搜索应用")
            val field = nodes().first { it.isEditable && it.isVisibleToUser }
            val rect = android.graphics.Rect().also(field::getBoundsInScreen)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action,
                    rect.centerX().toFloat(), rect.centerY().toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(inst.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            Thread.sleep(800)
            val info = inst.uiAutomation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            inst.uiAutomation.serviceInfo = info
            val ime = requireNotNull(inst.uiAutomation.windows.firstOrNull {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }) { "Actual IME window required" }
            val imeBounds = android.graphics.Rect().also(ime::getBoundsInScreen)
            var save = find("保存")
            while (!save.isClickable) save = requireNotNull(save.parent)
            val saveBounds = android.graphics.Rect().also(save::getBoundsInScreen)
            File(inst.targetContext.filesDir,"a9-live/ime-button-bounds.json").writeText(JSONObject()
                .put("saveBounds",saveBounds.toShortString()).put("imeBounds",imeBounds.toShortString())
                .put("saveHeightDp",saveBounds.height()/activity.resources.displayMetrics.density)
                .put("unobscured",saveBounds.bottom<=imeBounds.top).toString(2))
            shot("ime-confirmation-bounds")
            assertTrue("Save must remain entirely above keyboard: save=$saveBounds ime=$imeBounds", saveBounds.bottom <= imeBounds.top)
            assertTrue("Save target must retain 48dp height", saveBounds.height()/activity.resources.displayMetrics.density >= 47.9f)
        } finally { inst.runOnMainSync { activity.finish() } }
    }
    @Test fun actualImeAndPhysicalFoldPreserveWhitelistDraft() {
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val phase = File(inst.targetContext.filesDir, "a9-live/continuity-phase.txt")
        var saved: Set<String>? = null
        try {
            inst.runOnMainSync {
                activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                activity.setContent { ZeroTheme(darkTheme = false, dynamicColor = false) {
                    LiveSessionContent(LiveUiState(true, RealtimeState.LISTENING,
                        transcript = (0..49).map { TranscriptEntry(it % 2 == 0, "连续转写验收第 $it 项") }),
                        LivePageActions({}, {}, {}, {}, {}, { saved = it }), setOf("fixture.old"),
                        { listOf(AppEntry("fixture.reader", "验收阅读器"), AppEntry("fixture.old", "既有应用")) })
                } }
            }
            click("屏幕阅读白名单"); find("搜索应用")
            val field = nodes().first { it.isEditable && it.isVisibleToUser }
            val bounds = android.graphics.Rect().also(field::getBoundsInScreen)
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action,
                    bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(inst.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (android.os.SystemClock.uptimeMillis() < deadline &&
                androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true) Thread.sleep(100)
            assertTrue("Real IME must be visible", androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "reader") }))
            click("fixture.reader"); shot("continuity-ime-draft"); assertNull(saved)
            inst.runOnMainSync {
                androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            fun posture() = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                inst.uiAutomation.executeShellCommand("cmd device_state print-state")).use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }
            fun waitWidth(label: String, expectedState: String, predicate: (Int) -> Boolean): Int {
                phase.writeText(label)
                val until = android.os.SystemClock.uptimeMillis() + 300000
                while (android.os.SystemClock.uptimeMillis() < until) {
                    if (predicate(activity.resources.configuration.screenWidthDp) && posture() == expectedState) break
                    Thread.sleep(250)
                }
                val width = activity.resources.configuration.screenWidthDp
                assertTrue("Physical posture required: $label width=$width", predicate(width))
                assertEquals(expectedState, posture())
                find("搜索应用")
                assertEquals("reader", nodes().first { it.isEditable && it.isVisibleToUser }.text.toString())
                shot("continuity-$label")
                return width
            }
            val initialWidth = activity.resources.configuration.screenWidthDp
            assertTrue("Start on unfolded screen", initialWidth > 600)
            assertEquals("3", posture())
            val cover = waitWidth("await-cover", "0", { it in 280..420 })
            val unfolded = waitWidth("await-unfold", "3", { it > 600 })
            click("保存"); assertEquals(setOf("fixture.old", "fixture.reader"), saved)
            File(inst.targetContext.filesDir,"a9-live/continuity-report.json").writeText(JSONObject()
                .put("realImeVisible",true).put("initialWidthDp",initialWidth).put("coverWidthDp",cover)
                .put("unfoldedWidthDp",unfolded).put("draftPreserved",true).put("confirmedOnce",true)
                .put("scope","physical configuration change of production renderer; not NavHost/process-death acceptance").toString(2))
            phase.writeText("complete")
        } finally { inst.runOnMainSync { activity.finish() } }
    }
    @Test fun sixStatesControlsAndModalDraftRestoreInRealResourceMatrix() {
        val narrow = InstrumentationRegistry.getArguments().getString("requireSplit") == "true"
        val prefix = if (narrow) "split-" else ""
        val rows = JSONArray()
        val scales = if (narrow) listOf(2f) else listOf(1f, 2f)
        val palettes = if (narrow) listOf(false) else listOf(false, true)
        for (scale in scales) for (dark in palettes) for (dynamic in palettes) {
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            val ui = mutableStateOf(LiveUiState())
            val calls = mutableListOf<String>()
            var saved: Set<String>? = null
            val actions = LivePageActions({ calls.add("back") }, { calls.add("start") },
                { calls.add("interrupt") }, { calls.add("share") }, { calls.add("end") }, { saved = it })
            fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                LiveSessionContent(ui.value, actions, setOf("fixture.old"),
                    { listOf(AppEntry("fixture.reader", "验收阅读器"), AppEntry("fixture.old", "既有应用")) })
            } } } }
            try {
                mount()
                if (narrow) {
                    val automation = inst.uiAutomation
                    val info = automation.serviceInfo
                    info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    automation.serviceInfo = info
                    val deadline = android.os.SystemClock.uptimeMillis() + 180000
                    while (android.os.SystemClock.uptimeMillis() < deadline) {
                        val peerReady = inst.uiAutomation.windows.any {
                            it.root?.packageName?.toString() == "com.sec.android.app.popupcalculator"
                        }
                        if (activity.isInMultiWindowMode && peerReady) break
                        Thread.sleep(250)
                    }
                    assertTrue("Actual split window required", activity.isInMultiWindowMode)
                    assertTrue("Calculator peer must be ready", inst.uiAutomation.windows.any {
                        it.root?.packageName?.toString() == "com.sec.android.app.popupcalculator"
                    })
                    SplitWindowAcceptance.ensure(activity)
                }
                click("开始 Live"); assertEquals(listOf("start"), calls)
                for (state in RealtimeState.entries) {
                    if (narrow) SplitWindowAcceptance.ensure(activity)
                    inst.runOnMainSync { ui.value = LiveUiState(sessionActive = true, state = state,
                        transcript = listOf(TranscriptEntry(true, "独立转写，不发送语音或模型请求。"))) }
                    find(liveStatusLabel(ui.value)); find("结束 Live")
                    shot("$prefix$scale-$dark-$dynamic-${state.name}")
                }
                inst.runOnMainSync { ui.value = ui.value.copy(state = RealtimeState.SPEAKING) }
                click("停止说话"); click("打断"); click("屏幕共享"); click("返回"); click("结束 Live")
                assertEquals(listOf("start", "interrupt", "interrupt", "share", "back", "end"), calls)
                click("屏幕阅读白名单")
                find("搜索应用")
                val field = nodes().first { it.isEditable && it.isVisibleToUser }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "reader") }))
                click("fixture.reader"); assertNull(saved)
                shot("$prefix$scale-$dark-$dynamic-draft")
                val monitor = inst.addMonitor(VisualAcceptanceActivity::class.java.name, null, false)
                try {
                    inst.runOnMainSync { VisualAcceptanceActivity.fontScaleForNextLaunch = scale; activity.recreate() }
                    activity = requireNotNull(inst.waitForMonitorWithTimeout(monitor, 10000)) as VisualAcceptanceActivity
                } finally { inst.removeMonitor(monitor) }
                mount()
                if (narrow && inst.uiAutomation.rootInActiveWindow?.packageName?.toString() != activity.packageName) {
                    SplitWindowAcceptance.ensure(activity)
                }
                find("搜索应用")
                assertEquals("reader", nodes().first { it.isEditable && it.isVisibleToUser }.text.toString())
                shot("$prefix$scale-$dark-$dynamic-restored")
                click("保存"); assertEquals(setOf("fixture.old", "fixture.reader"), saved)
                click("屏幕阅读白名单")
                var selectedNode = find("fixture.reader")
                while (!selectedNode.isCheckable) selectedNode = requireNotNull(selectedNode.parent)
                assertTrue("Reopening must use confirmed whitelist, not stale initial props", selectedNode.isChecked)
                click("取消")
                assertEquals(setOf("fixture.old", "fixture.reader"), saved)
                rows.put(JSONObject().put("scale", scale).put("dark", dark).put("dynamic", dynamic)
                    .put("widthDp", activity.resources.configuration.screenWidthDp)
                    .put("multiWindow", activity.isInMultiWindowMode).put("draftRestored", true)
                    .put("callbacks", calls.joinToString()).put("states", RealtimeState.entries.size))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir, "a9-live/${prefix}matrix.json").writeText(rows.toString(2))
    }

    @Test fun reducedOrbIsStaticAndActualBackgroundStopsWindowFrames() {
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val reduced = mutableStateOf(false)
        val frames = CopyOnWriteArrayList<Long>()
        val listener = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        try {
            inst.runOnMainSync {
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
                activity.setContent { ZeroTheme(darkTheme = false, dynamicColor = false) {
                    LiveSessionContent(LiveUiState(true, RealtimeState.LISTENING),
                        LivePageActions({}, {}, {}, {}, {}, {}), emptySet(), { emptyList() }, reduceMotion = reduced.value)
                } }
            }
            find("Live 状态球"); Thread.sleep(500)
            fun orbImage(): android.graphics.Bitmap {
                val bounds = android.graphics.Rect().also { find("Live 状态球").getBoundsInScreen(it) }
                val image = requireNotNull(inst.uiAutomation.takeScreenshot())
                val crop = android.graphics.Bitmap.createBitmap(image, bounds.left, bounds.top, bounds.width(), bounds.height())
                image.recycle(); return crop
            }
            val moving = orbImage(); Thread.sleep(350); val moved = orbImage()
            assertFalse("Active pulse must change real pixels", moving.sameAs(moved)); moving.recycle(); moved.recycle()
            inst.runOnMainSync { reduced.value = true }; Thread.sleep(300)
            val still = orbImage(); Thread.sleep(350); val stillAgain = orbImage()
            assertTrue("Reduced-motion override must freeze orb pixels", still.sameAs(stillAgain)); still.recycle(); stillAgain.recycle()
            shot("reduced-static")
            inst.runOnMainSync { reduced.value = false }; Thread.sleep(600)
            val activeFrames = frames.toList()
            assertTrue("Active window frame metrics required", activeFrames.size >= 5)
            inst.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
            Thread.sleep(700); val pausedCount = frames.size; Thread.sleep(700)
            assertEquals("Background window must stop producing frames", pausedCount, frames.size)
            val sorted = activeFrames.sorted()
            File(inst.targetContext.filesDir, "a9-live/motion-report.json").writeText(JSONObject()
                .put("activeSamples", activeFrames.size).put("totalDurationP95Ms", sorted[(sorted.size - 1) * 95 / 100] / 1_000_000.0)
                .put("reducedOverridePixelsStatic", true).put("actualBackgroundAdditionalFrames", frames.size - pausedCount)
                .put("scope", "isolated production renderer; frame diagnostics are not a formal performance gate").toString(2))
        } finally { inst.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener); activity.finish() } }
    }
}
