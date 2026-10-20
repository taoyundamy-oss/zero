package com.zero.v2.app.acceptance

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.live.*
import com.zero.v2.app.ui.theme.ZeroTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Physical fold with production reader and synthetic text. No voice/model quality claim. */
class LiveFoldReadingAcceptanceTest {
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
    private fun posture(): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        inst.uiAutomation.executeShellCommand("cmd device_state print-state")
    ).use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Test fun physicalFoldRoundTripPreservesLongReadingEntryAfterTrim() {
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val dir = File(inst.targetContext.filesDir, "a9-fold-reading").apply { mkdirs() }
        val phase = File(dir, "phase.txt")
        val entries = mutableStateOf((0..49).map { TranscriptEntry(it % 2 == 0,
            "折叠阅读-$it\n" + "这段合成转写用于检查同一条目的阅读连续性，不能代表真实语音质量。".repeat(5),
            it.toLong(), "fold-$it") })
        fun visibleTexts() = nodes().filter { it.isVisibleToUser && it.text?.startsWith("折叠阅读-") == true }
            .sortedBy { android.graphics.Rect().also(it::getBoundsInScreen).top }.map { it.text.toString() }
        fun shot(name: String) {
            requireNotNull(inst.uiAutomation.takeScreenshot()).let { bitmap ->
                File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        fun waitPosture(label: String, state: String, predicate: (Int) -> Boolean): Int {
            phase.writeText(label)
            val deadline = android.os.SystemClock.uptimeMillis() + 300000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val width = activity.resources.configuration.screenWidthDp
                if (posture() == state && predicate(width) && visibleTexts().isNotEmpty()) return width
                Thread.sleep(250)
            }
            fail("Physical window not ready: $label")
            return -1
        }
        try {
            inst.runOnMainSync { activity.setContent { ZeroTheme {
                LiveSessionContent(LiveUiState(transcript = entries.value), LivePageActions({}, {}, {}, {}, {}, {}),
                    emptySet(), { emptyList() })
            } } }
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (visibleTexts().isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            val initialWidth = waitPosture("await-initial-unfold", "3") { it > 600 }
            repeat(4) {
                assertTrue(nodes().first { it.isVisibleToUser && it.isScrollable }
                    .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                Thread.sleep(250)
            }
            val anchor = visibleTexts().first()
            val beforeTexts = visibleTexts().map { it.substringBefore('\n') }
            File(dir, "probe.json").writeText(JSONObject().put("anchor", anchor.substringBefore('\n'))
                .put("beforeTexts", org.json.JSONArray(beforeTexts)).put("initialWidthDp", initialWidth).toString(2))
            shot("before")
            val coverWidth = waitPosture("await-cover", "0") { it in 280..420 }
            Thread.sleep(500)
            shot("cover")
            File(dir, "probe.json").writeText(JSONObject().put("anchor", anchor.substringBefore('\n'))
                .put("beforeTexts", org.json.JSONArray(beforeTexts)).put("coverTexts", org.json.JSONArray(visibleTexts().map { it.substringBefore('\n') }))
                .put("activityDestroyed", activity.isDestroyed).put("initialWidthDp", initialWidth).put("coverWidthDp", coverWidth).toString(2))
            assertTrue("Same reading entry on cover: ${anchor.substringBefore('\n')} -> ${visibleTexts().map { it.substringBefore('\n') }}", anchor in visibleTexts())
            inst.runOnMainSync { entries.value = entries.value.drop(3) + (50..52).map {
                TranscriptEntry(true, "折叠阅读-$it\n" + "新增合成转写。".repeat(8), it.toLong(), "fold-$it")
            } }
            Thread.sleep(500)
            assertTrue("Appending bounded transcript must retain the cover reading entry", anchor in visibleTexts())
            shot("cover-trim")
            val unfoldedWidth = waitPosture("await-unfold", "3") { it > 600 }
            Thread.sleep(500)
            assertTrue("Same reading entry after physical round trip", anchor in visibleTexts())
            shot("unfolded")
            File(dir, "report.json").writeText(JSONObject().put("initialWidthDp", initialWidth)
                .put("coverWidthDp", coverWidth).put("unfoldedWidthDp", unfoldedWidth)
                .put("entry", anchor.substringBefore('\n')).put("trimmed", 3).put("entryPreserved", true)
                .put("scope", "physical fold; production renderer; synthetic text; entry continuity, not pixel/character continuity")
                .toString(2))
            phase.writeText("complete")
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
