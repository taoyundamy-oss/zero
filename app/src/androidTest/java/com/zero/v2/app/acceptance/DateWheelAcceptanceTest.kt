package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.date.WheelDateField
import com.zero.v2.app.ui.date.WheelDateTimeField
import com.zero.v2.app.ui.date.WheelTimeField
import com.zero.v2.app.ui.settings.MemoryReviewEditDialog
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.entity.MemoryReviewTimes
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production wheels/form, synthetic values, no database, normal Application startup suppressed. */
class DateWheelAcceptanceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val zone get() = ZoneId.systemDefault()
    private val original get() = LocalDate.of(2026, 1, 31).atTime(12, 30, 9).atZone(zone).toInstant().toEpochMilli()
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(inst.uiAutomation.rootInActiveWindow)
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(60) {
            nodes().firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Missing UI: $text")
    }
    /** Native NumberPicker virtual providers do not expose View.contentDescription on API35.
     * Pair the visible unit label with its actual native picker; never scroll while searching. */
    private fun wheel(label: String): AccessibilityNodeInfo {
        repeat(60) {
            val all = nodes()
            val unit = all.firstOrNull { it.text?.toString() == label }
            if (unit != null) {
                val bounds = Rect(); unit.getBoundsInScreen(bounds)
                val picker = all.filter { it.className?.toString() == "android.widget.NumberPicker" }
                    .map { node -> val box = Rect(); node.getBoundsInScreen(box); node to box }
                    .filter { (_, box) -> bounds.centerX() in box.left..box.right && box.top >= bounds.bottom }
                    .minByOrNull { (_, box) -> box.top - bounds.bottom }?.first
                if (picker != null) return picker
            }
            Thread.sleep(100)
        }
        error("Missing native wheel $label: " + nodes().joinToString { "${it.className}:${it.text}:${it.contentDescription}" })
    }
    private fun button(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }
    private fun click(text: String) {
        assertTrue(button(find(text)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        inst.waitForIdleSync(); Thread.sleep(200)
    }
    private fun fixture(content: @Composable () -> Unit, verify: () -> Unit) {
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            inst.runOnMainSync { activity.setContent { ZeroTheme { content() } } }
            inst.waitForIdleSync(); verify()
        } finally { inst.runOnMainSync { activity.finish() } }
    }
    private fun reason() {
        find("复核理由")
        assertTrue(nodes().last { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "合成人工核对")
        }))
        inst.waitForIdleSync(); Thread.sleep(200)
    }
    private fun dateText(label: String, time: Long) = "$label：" + Instant.ofEpochMilli(time).atZone(zone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))

    @Test fun secondsPrecisionOldMemoryCanSaveWithoutTypingDates() {
        val saved = AtomicReference<MemoryReviewTimes>()
        val memory = MemoryEntity("date-fixture", "合成规范", type = MemoryType.DIRECTIVE,
            occurredAt = original, validFrom = original)
        fixture({ MemoryReviewEditDialog(memory, false, null, {}, { _, _, _, _, times -> saved.set(times) }) }) {
            reason(); click("保存提案")
            assertEquals(original, saved.get().occurredAt)
            assertEquals(original, saved.get().validFrom)
            assertNull(saved.get().validUntil)
        }
    }
    @Test fun openingAndConfirmingKeepsExistingMilliseconds() {
        val saved = AtomicReference<Long>()
        val at = original + 123
        fixture({ WheelDateTimeField("发生时间", at) { saved.set(it) } }) {
            click(dateText("发生时间", at)); wheel("年"); wheel("秒"); click("确定")
            assertEquals(at, saved.get().toLong())
        }
    }
    @Test fun monthScrollClampsDayAndDoesNotApplyBeforeConfirm() {
        val saved = AtomicReference<Long>()
        fixture({ WheelDateTimeField("发生时间", original) { saved.set(it) } }) {
            click(dateText("发生时间", original))
            assertTrue(wheel("月").performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            inst.waitForIdleSync(); Thread.sleep(500)
            assertNull(saved.get()); click("确定")
            val result = Instant.ofEpochMilli(saved.get()).atZone(zone)
            assertEquals(2, result.monthValue); assertEquals(28, result.dayOfMonth)
        }
    }
    @Test fun cancelDoesNotApplyAndClearKeepsUnknownUntilSelectionConfirmed() {
        val callbacks = AtomicInteger()
        val last = AtomicReference<Long>(original)
        fixture({
            var value by remember { mutableStateOf<Long?>(original) }
            WheelDateTimeField("发生时间", value) { value = it; last.set(it); callbacks.incrementAndGet() }
        }) {
            click(dateText("发生时间", original)); click("取消"); assertEquals(0, callbacks.get())
            click("清空发生时间"); assertNull(last.get()); assertEquals(1, callbacks.get())
            click("发生时间：未知／未指定"); click("取消")
            assertNull(last.get()); assertEquals(1, callbacks.get())
        }
    }
    @Test fun invalidRangeNamesFieldAndClearingEndAllowsSaving() {
        val saved = AtomicReference<MemoryReviewTimes>()
        val memory = MemoryEntity("range-fixture", "合成事实", type = MemoryType.FACT,
            validFrom = original, validUntil = original)
        fixture({ MemoryReviewEditDialog(memory, false, null, {}, { _, _, _, _, times -> saved.set(times) }) }) {
            reason(); find("失效时间：必须晚于生效时间")
            assertFalse(button(find("保存提案")).isEnabled)
            click("清空失效时间"); click("保存提案")
            assertEquals(original, saved.get().validFrom); assertNull(saved.get().validUntil)
        }
    }
    @Test fun dateOnlyAndDailyClockUseWheelsWithoutEditableDateFields() {
        val clock = AtomicReference<String>()
        fixture({ Column {
            WheelDateField("开始日期", LocalDate.of(2026, 10, 18)) {}
            WheelTimeField("执行时间", "12:30") { clock.set(it) }
        } }) {
            click("开始日期：2026-10-18"); wheel("日"); click("取消")
            click("执行时间：12:30"); wheel("时")
            assertTrue(wheel("分").performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            inst.waitForIdleSync(); Thread.sleep(500); click("确定")
            assertEquals("12:31", clock.get())
        }
    }
}
