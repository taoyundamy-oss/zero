package com.zero.v2.app.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.LlmProfileStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Host kills only the checkpointed user0 process. No personal preference file is written. */
class ProfileProcessRecoveryAcceptanceTest {
    @Test fun realProcessProfileAndDraftRecovery() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("profileRecoveryPhase")
        org.junit.Assume.assumeTrue(phase in setOf("seed", "verify"))
        val nonce = requireNotNull(args.getString("profileRecoveryNonce"))
        require(nonce.matches(Regex("[a-zA-Z0-9_]{1,64}")))
        val prefName = "a8_s3_profile_recovery_$nonce"
        val context = inst.targetContext
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences(if (name == "zero_llm_profiles") prefName else name, mode)
        }
        val entry = EntryPointAccessors.fromApplication(context, RegenerationAcceptanceEntryPoint::class.java)
        val store = LlmProfileStore(scoped, entry.traces())
        val expected = LlmProfile.localQwen("http://127.0.0.1:8080/v1", "a8_recovery_model").copy(
            id = "a8_s3_recovery_$nonce", displayName = "独立配置恢复", enabled = false,
            temperature = .63f, maxTokens = 512, contextWindow = 6144, priority = 11,
            apiKeyRef = "a8:unconfigured:$nonce", voice = "preserved-inactive-voice",
            connectionId = "profile:a8_s3_recovery_$nonce")
        val report = java.io.File(context.filesDir, "a8-s3-process-recovery.json")
        if (phase == "verify") {
            val checkpoint = JSONObject(report.readText())
            assertEquals(nonce, checkpoint.getString("nonce"))
            assertNotEquals(checkpoint.getInt("pid"), android.os.Process.myPid())
            assertEquals(expected, store.get(expected.id))
            checkpoint.put("verifyPid", android.os.Process.myPid()).put("profileEquals", true)
            report.writeText(checkpoint.toString(2))
            assertTrue(context.deleteSharedPreferences(prefName))
            return@runBlocking
        }
        store.upsert(expected)
        assertEquals(expected, store.get(expected.id))
        val disk = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$prefName.xml")
        withTimeout(10_000) { while (!disk.exists() || !disk.readText().contains(expected.id)) delay(25) }
        inst.startActivitySync(Intent(context, com.zero.v2.app.MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        val activity = inst.startActivitySync(Intent(context, VisualAcceptanceActivity::class.java)
            .putExtra("acceptancePage", "profile-editor").putExtra("acceptanceWidth", 1200)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            fun visit(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null || !n.refresh()) return null
                if (n.isVisibleToUser && predicate(n)) return n
                for (i in 0 until n.childCount) visit(n.getChild(i))?.let { return it }
                return null
            }
            repeat(100) { attempt ->
                visit(inst.uiAutomation.rootInActiveWindow)?.let { return it }
                if (attempt % 3 == 2) {
                    fun scroll(n: AccessibilityNodeInfo?): Boolean {
                        if (n == null) return false
                        if (n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                        for (i in 0 until n.childCount) if (scroll(n.getChild(i))) return true
                        return false
                    }
                    scroll(inst.uiAutomation.rootInActiveWindow)
                }
                Thread.sleep(60)
            }
            error("Recovery fixture control unavailable")
        }
        fun set(node: AccessibilityNodeInfo, text: String) {
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
        }
        find { it.isEditable && it.text?.toString() == "isolated" }
        set(find { it.isEditable && it.text.isNullOrEmpty() }, "isolated-unsaved-key")
        set(find { it.isEditable && it.text?.toString() == "2048" }, "1024")
        assertTrue(inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        withTimeout(10_000) {
            while (activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) delay(25)
        }
        report.writeText(JSONObject().put("nonce", nonce).put("phase", "ready-for-host-kill")
            .put("pid", android.os.Process.myPid()).put("uid", android.os.Process.myUid())
            .put("taskId", activity.taskId).put("isolatedPreferenceFile", prefName)
            .put("profilePersisted", true).put("activityStopped", true).toString(2))
        // Expected host SIGKILL: this seed phase deliberately does not produce a green JUnit result.
        awaitCancellation()
    }
}
