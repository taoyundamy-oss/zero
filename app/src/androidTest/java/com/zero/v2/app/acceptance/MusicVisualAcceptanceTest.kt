package com.zero.v2.app.acceptance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.music.PlaybackState
import com.zero.v2.app.ui.music.MusicLibraryContent
import com.zero.v2.app.ui.music.MusicLibraryUiState
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.FunctionResult
import com.zero.v2.core.common.ToolCall
import com.zero.v2.core.data.entity.*
import com.zero.v2.core.moduleapi.ToolDispatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Production content and isolated command owner; never reads or plays personal music. */
class MusicVisualAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: VisualAcceptanceActivity
    private val split get() = InstrumentationRegistry.getArguments().getString("requireSplit") == "true"
    private val output get() = if (split) "a8-music-split-ui" else "a8-music-ui"
    private var back = 0
    private val commands = mutableListOf<String>()
    private val dispatcher = object : ToolDispatcher {
        override suspend fun execute(name: String, arguments: Map<String, Any?>): FunctionResult {
            commands.add(name); return FunctionResult.success("synthetic success")
        }
        override suspend fun execute(toolCall: ToolCall) = error("Unexpected call")
        override suspend fun executeBatch(toolCalls: List<ToolCall>) = error("Unexpected batch")
    }

    @Test fun fullPageDialogsTailAndFailures() = runBlocking {
        val report = JSONArray()
        MusicStateAcceptanceTest().fixture(dispatcher) { model, _ ->
            try {
                for (width in if (split) listOf(720) else listOf(360, 720)) for (dark in listOf(false, true))
                    for (dynamic in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                        val name = "$width-$dark-$dynamic-$scale"
                        val selectedCase = InstrumentationRegistry.getArguments().getString("selectedCase")
                        if (selectedCase != null && selectedCase != name) continue
                        val projection = mutableStateOf(MusicLibraryUiState())
                        var launched = false
                        fun page(state: MusicLibraryUiState) {
                            if (launched) {
                                instrumentation.runOnMainSync { projection.value = state }
                                settle();return
                            }
                            launched = true
                            projection.value = state
                            val previous = if (::activity.isInitialized) activity else null
                            if (split && previous != null) {
                                activity = SplitWindowAcceptance.recreate(previous, scale)
                            } else {
                                instrumentation.runOnMainSync { previous?.finish(); VisualAcceptanceActivity.fontScaleForNextLaunch = scale }
                                activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, VisualAcceptanceActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
                            }
                            assertEquals(scale, activity.resources.configuration.fontScale, 0f)
                            if (split) SplitWindowAcceptance.ensure(activity)
                            val actualWidth = activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density
                            assertTrue(if (split) activity.isInMultiWindowMode && actualWidth in 280f..599f else actualWidth >= 720)
                            instrumentation.runOnMainSync {
                                activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                                    val colors = MaterialTheme.colorScheme
                                    SideEffect { activity.palette = colors }
                                    Box(Modifier.fillMaxSize()) { Box(Modifier.widthIn(max = width.dp).fillMaxSize()) {
                                        key(projection.value) { MusicLibraryContent(projection.value, { back++ }, model) }
                                    } }
                                } }
                            }
                            settle()
                        }
                        val tracks = (0..11).map { MusicTrackEntity("synthetic-$it", "合成子目录/曲目$it.mp3",
                            "合成音乐长标题用于验证窄屏大字号阅读 $it", artist = "合成艺术家", addedAt = 0, durationMs = 180000) }
                        val state = MusicLibraryUiState(dirConfigured = true, tracks = tracks, loading = false,
                            playback = PlaybackState(trackId = "synthetic-0", title = "合成正在播放的很长曲名，必须完整阅读", queueSize = 12),
                            playlists = listOf(PlaylistEntity("synthetic-list", "合成长歌单名称用于大字号阅读", createdAt = 0) to 12),
                            schedules = listOf(ScheduledPlaybackEntity("synthetic-schedule", 0, hint = "合成意图", mode = "AUTO")))
                        page(state);shot("$name-top")
                        assertTrue(nodes().any { it.text?.toString()?.contains("合成正在播放的很长曲名") == true })
                        click("播放", description = true)
                        val palette = requireNotNull(activity.palette)
                        assertTrue(contrast(palette.onSurface, palette.surfaceContainerLow) >= 4.5)
                        assertTrue(contrast(palette.onSurfaceVariant, palette.surfaceContainerLow) >= 4.5)
                        assertTrue(contrast(palette.onErrorContainer, palette.errorContainer) >= 4.5)
                        reach("从列表建单");click("从列表建单")
                        await("歌单名");shot("$name-create-dialog");click("取消")
                        reach("删除歌单", true);click("删除歌单", true)
                        await("取消");shot("$name-delete-dialog");click("取消")
                        reach("新建");click("新建");await("取消")
                        shot("$name-schedule-dialog");click("取消")
                        reach(tracks.last().displayTitle);shot("$name-tail")
                        val before = back;click("返回", true);assertEquals(before + 1, back)
                        page(MusicLibraryUiState(loading = false, readError = "合成读取失败", operationError = "合成操作失败"))
                        await("合成读取失败");await("合成操作失败")
                        assertFalse(nodes().any { it.text?.toString()?.startsWith("暂无曲目") == true })
                        shot("$name-error");click("重新读取")
                        page(MusicLibraryUiState(loading = false));await("暂无曲目，扫描后显示音乐。");shot("$name-empty")
                        report.put(JSONObject().put("name", name).put("contentAndTail", true).put("dialogsCancelled", true)
                            .put("readAndCommandFailuresVisible", true).put("resourceFontScale", scale)
                            .put("actualWidthDp", activity.resources.displayMetrics.widthPixels / activity.resources.displayMetrics.density)
                            .put("actualMultiWindow", activity.isInMultiWindowMode))
                    }
                File(instrumentation.targetContext.filesDir, "$output/report.json").writeText(JSONObject()
                    .put("passed", true).put("cases", report).put("personalDataWrites", 0).put("realPlayback", false)
                    .put("providerRequests", 0).toString(2))
            } finally { if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) { if (n == null || !n.refresh()) return; out.add(n); for (i in 0 until n.childCount) visit(n.getChild(i)) }
        visit(automation.rootInActiveWindow);return out
    }
    private fun matches(n: AccessibilityNodeInfo, label: String, description: Boolean) =
        if (description) n.contentDescription?.toString() == label else n.text?.toString() == label
    private fun await(label: String) { repeat(50) { if (nodes().any { n -> matches(n,label,false) }) return; Thread.sleep(100) };fail("Missing synthetic UI action: $label") }
    private fun reach(label: String, description: Boolean = false) {
        repeat(30) { val tree = nodes();if (tree.any { n -> matches(n,label,description) }) return
            val scroll = tree.firstOrNull { n -> n.isScrollable && n.actionList.any { a -> a.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } }
            assertNotNull("No scroll for synthetic tail",scroll);assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));settle() }
        fail("Synthetic tail inaccessible")
    }
    private fun click(label: String, description: Boolean = false) {
        reach(label,description)
        var node = nodes().first { matches(it,label,description) }
        while (!node.isClickable) node = requireNotNull(node.parent)
        val bounds = Rect().also(node::getBoundsInScreen);val density = activity.resources.displayMetrics.density
        assertTrue("Small synthetic action: $label $bounds", bounds.width()/density >= 48 && bounds.height()/density >= 48)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));settle()
    }
    private fun settle() { instrumentation.waitForIdleSync();automation.waitForIdle(200,5000);Thread.sleep(150) }
    private fun contrast(a: Color,b: Color): Double = (maxOf(a.luminance(),b.luminance())+.05)/(minOf(a.luminance(),b.luminance())+.05)
    private fun shot(name: String) {
        fun capture(): android.graphics.Bitmap {
            val full = requireNotNull(automation.takeScreenshot())
            if (!split) return full
            val bounds = SplitWindowAcceptance.bounds(activity)
            return android.graphics.Bitmap.createBitmap(full,bounds.left,bounds.top,bounds.width(),bounds.height()).also { full.recycle() }
        }
        Thread.sleep(1000) // Samsung popup transition can outlast accessibility idle, especially at 200%.
        var bitmap = capture()
        var stable = false
        for (attempt in 0 until 20) {
            Thread.sleep(150)
            val next = capture()
            if (bitmap.sameAs(next)) { next.recycle(); stable = true; break }
            bitmap.recycle(); bitmap = next
        }
        assertTrue("Screenshot must settle before visual review: $name", stable)
        val file = File(instrumentation.targetContext.filesDir,"$output/$name.png")
        file.parentFile!!.mkdirs();file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
