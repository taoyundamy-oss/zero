package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.MemoryReviewScreen
import com.zero.v2.app.ui.settings.MemoryReviewViewModel
import com.zero.v2.app.ui.theme.ZeroTheme
import com.zero.v2.core.common.MemoryType
import com.zero.v2.core.common.MemoryStatus
import com.zero.v2.core.data.db.ZeroDatabase
import com.zero.v2.core.data.entity.MemoryEntity
import com.zero.v2.core.data.repository.MemoryReviewRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Production screen/ViewModel, synthetic in-memory database, normal app startup suppressed by runner. */
class MemoryReviewUiTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isVisibleToUser) add(n)
            repeat(n.childCount) { visit(n.getChild(it)) }
        }
        visit(inst.uiAutomation.rootInActiveWindow)
    }
    private fun find(text: String): AccessibilityNodeInfo {
        repeat(60) {
            nodes().firstOrNull { it.text?.toString() == text }?.let { return it }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            Thread.sleep(100)
        }
        error("Missing UI: $text")
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }
    private fun click(text: String) {
        repeat(30) {
            val node = clickable(find(text))
            if (node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                inst.waitForIdleSync()
                Thread.sleep(150)
                return
            }
            Thread.sleep(100)
        }
        error("UI action did not become enabled: $text")
    }
    private fun fixture(block: (ZeroDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(inst.targetContext, ZeroDatabase::class.java).build()
        val owner = ViewModelStore()
        val activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        try {
            runBlocking { db.memoryDao().insert(MemoryEntity("review-fixture", "合成：阅读时偏好柔和灯光")) }
            inst.runOnMainSync {
                val model = MemoryReviewViewModel(db.memoryDao(), MemoryReviewRepository(db.memoryReviewDao()), com.zero.v2.core.data.store.HistoricalSourceMigration(db))
                owner.put("review", model)
                activity.setContent { ZeroTheme { MemoryReviewScreen({}, model) } }
            }
            find("历史记忆复核")
            block(db)
        } finally {
            inst.runOnMainSync { owner.clear(); activity.finish() }
            db.close()
        }
    }

    @Test fun repeatedPreferenceScanShowsSourcesWithoutWritingProposals() = fixture { db ->
        runBlocking {
            db.conversationDao().insert(com.zero.v2.core.data.entity.ConversationEntity("suggest-conversation", "合成"))
            for (id in listOf("suggest-one", "suggest-two")) {
                val text = "合成：只有写作时我喜欢安静。"
                db.messageDao().insert(com.zero.v2.core.data.entity.MessageEntity(id, "suggest-conversation", "user", text,
                    source = "CHAT_WINDOW"))
                db.memoryDao().insert(MemoryEntity(id, text, type = MemoryType.PREFERENCE, origin = "USER_QUOTE",
                    sourceMessageId = id, sourceConversationId = "suggest-conversation"))
            }
        }
        Thread.sleep(300)
        click("查找重复偏好建议（仅本地）")
        find("本地核对完成：1组重复明示偏好；最多核对200个来源，建议未保存、未生效")
        click("查看支持来源")
        find("编辑为人工复核提案")
        runBlocking {
            assertTrue(db.memoryReviewDao().observe().first().isEmpty())
            assertEquals(3, db.memoryDao().count())
        }
    }

    @Test fun createDraftThenRejectDoesNotRewriteHistory() = fixture { db ->
        click("选择记忆创建提案")
        click("合成：阅读时偏好柔和灯光")
        click("偏好")
        find("复核理由")
        val edit = nodes().last { it.isEditable }
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "合成分类复核")
        }))
        click("保存提案")
        find("提案已保存；请核对后确认")
        runBlocking {
            assertEquals(MemoryType.EVENT, db.memoryDao().getById("review-fixture")!!.type)
            assertEquals("PENDING", db.memoryReviewDao().observe().first().single().state)
        }
        click("合成：阅读时偏好柔和灯光")
        click("拒绝提案")
        find("已拒绝，历史记忆未修改")
        runBlocking {
            assertEquals("REJECTED", db.memoryReviewDao().observe().first().single().state)
            assertEquals(MemoryStatus.ACTIVE, db.memoryDao().getById("review-fixture")!!.status)
        }
    }

    @Test fun confirmationRequiresExplicitCheckboxAndCreatesSeparateVersion() = fixture { db ->
        val id = runBlocking {
            val m = db.memoryDao().getById("review-fixture")!!
            db.memoryReviewDao().propose(m, m.content, MemoryType.PREFERENCE, "阅读时", "合成复核", 1)!!
        }
        click("合成：阅读时偏好柔和灯光")
        assertFalse(clickable(find("确认并生成新版本")).isEnabled)
        find("我确认内容、分类、范围与时间，并同意归档旧版本")
        val checkbox = nodes().first { it.isCheckable }
        assertTrue(checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        inst.waitForIdleSync()
        Thread.sleep(1000) // Allow dialog animation/compositor to settle before visual evidence.
        val file = java.io.File(inst.targetContext.getExternalFilesDir(null), "p5-review-confirm.png")
        file.outputStream().use { inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        click("确认并生成新版本")
        find("已确认新版本，旧原文已归档")
        runBlocking {
            assertEquals("ACCEPTED", db.memoryReviewDao().get(id)!!.state)
            assertEquals("USER_CONFIRMED", db.memoryDao().getById("review:$id")!!.origin)
            assertEquals(MemoryStatus.ARCHIVED, db.memoryDao().getById("review-fixture")!!.status)
        }
    }
}
