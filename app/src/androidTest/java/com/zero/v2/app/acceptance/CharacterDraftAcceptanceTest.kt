package com.zero.v2.app.acceptance

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import com.zero.v2.app.ui.settings.*
import com.zero.v2.app.ui.theme.ZeroTheme
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CharacterDraftAcceptanceTest {
    @Test fun allDraftFieldsAndFailedSaveSurviveRecreationInEightConditions() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val report = org.json.JSONArray()
        for (scale in listOf(1f, 2f)) for (dark in listOf(false, true)) for (dynamic in listOf(false, true)) {
            VisualAcceptanceActivity.fontScaleForNextLaunch = scale
            var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
            val action = mutableStateOf(CharacterActionState(loading = false))
            var submitted: List<String?>? = null
            var cancels = 0
            val commands = CharacterPageActions({}, {}, { cancels++ }, { name, desc, prompt ->
                submitted = listOf(name, desc, prompt)
                action.value = CharacterActionState(loading = false, error = "隔离测试：保存失败，草稿保留")
            }, {}, {}, {}, {}, {}, {}, {})
            fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme(darkTheme = dark, dynamicColor = dynamic) {
                CharacterManageContent(emptyList(), CharacterEditState.Creating, CharacterStateUiState(loaded = true), action.value, {}, commands)
            } } } }
            fun nodes(): List<AccessibilityNodeInfo> {
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun visit(n: AccessibilityNodeInfo?) { if(n == null || !n.refresh()) return; out.add(n); for(i in 0 until n.childCount) visit(n.getChild(i)) }
                visit(inst.uiAutomation.rootInActiveWindow); return out
            }
            fun field(label: String): AccessibilityNodeInfo {
                fun editable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if(n.isEditable && n.isVisibleToUser) return n
                    for(i in 0 until n.childCount) n.getChild(i)?.let { editable(it)?.let { found -> return found } }
                    return null
                }
                repeat(80) { attempt ->
                    nodes().firstOrNull { it.contentDescription?.toString()?.contains(label) == true && it.isVisibleToUser }
                        ?.let { marked ->
                            var owner: AccessibilityNodeInfo? = marked
                            while(owner != null) {
                                if(owner.isEditable) return owner
                                owner = owner.parent
                            }
                            editable(marked)?.let { found -> return found }
                        }
                    if(attempt % 3 == 2) nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(80)
                }
                File(inst.targetContext.filesDir,"a8-character-draft-missing.txt").writeText(nodes().joinToString("\n") {
                    "${it.className}|${it.text}|${it.contentDescription}|editable=${it.isEditable}|visible=${it.isVisibleToUser}"
                })
                error("Missing editable draft: $label")
            }
            fun click(label: String) {
                var n = nodes().first { it.text?.toString() == label && it.isVisibleToUser }
                while(!n.isClickable) n = requireNotNull(n.parent)
                assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(200)
            }
            val values = linkedMapOf("角色名称草稿" to "isolated draft $scale-$dark-$dynamic",
                "角色描述草稿" to "第一行描述\n第二行独立草稿",
                "角色说明草稿" to "独立角色说明\n".repeat(12))
            try {
                SplitWindowAcceptance.ensure(activity); mount()
                assertEquals(scale, activity.resources.configuration.fontScale, 0f)
                assertTrue(activity.isInMultiWindowMode)
                for((label, value) in values) assertTrue(field(label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
                click("保存"); assertEquals(values.values.toList(), submitted)
                activity = SplitWindowAcceptance.recreate(activity, scale); mount()
                // Check each field without replacing the restored content. Scroll back before each lookup.
                for((label, value) in values) {
                    repeat(8) { nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); Thread.sleep(50) }
                    assertEquals("Draft must survive failed save and recreation: $label", value, field(label).text?.toString())
                }
                inst.runOnMainSync { action.value = action.value.copy(busy = true) }; Thread.sleep(200)
                assertTrue(nodes().none { it.isEditable && it.isEnabled })
                assertTrue(nodes().any { it.text?.toString() == "正在保存…" })
                inst.runOnMainSync { action.value = action.value.copy(busy = false) }; Thread.sleep(200)
                click("取消"); assertEquals(1, cancels)
                report.put(org.json.JSONObject().put("fontScale",scale).put("dark",dark).put("dynamic",dynamic)
                    .put("threeFieldsRestored",true).put("failedSaveRetainsDraft",true).put("busyControls",true))
            } finally { inst.runOnMainSync { activity.finish() } }
        }
        File(inst.targetContext.filesDir,"a8-character-draft-report.json").writeText(report.toString(2))
    }
    @Test fun unsavedRoleNameSurvivesActualActivityRecreation() {
        val inst = InstrumentationRegistry.getInstrumentation()
        VisualAcceptanceActivity.fontScaleForNextLaunch = 2f
        var activity = inst.startActivitySync(Intent(inst.targetContext, VisualAcceptanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as VisualAcceptanceActivity
        val commands = CharacterPageActions({}, {}, {}, { _,_,_ -> }, {}, {}, {}, {}, {}, {}, {})
        fun mount() { inst.runOnMainSync { activity.setContent { ZeroTheme {
            CharacterManageContent(emptyList(), CharacterEditState.Creating, CharacterStateUiState(loaded = true), CharacterActionState(loading = false), {}, commands)
        } } } }
        fun nodes(): List<AccessibilityNodeInfo> {
            val out=mutableListOf<AccessibilityNodeInfo>()
            fun visit(n:AccessibilityNodeInfo?) { if(n==null || !n.refresh())return;out.add(n);for(i in 0 until n.childCount)visit(n.getChild(i)) }
            visit(inst.uiAutomation.rootInActiveWindow);return out
        }
        try {
            SplitWindowAcceptance.ensure(activity); mount()
            var field: AccessibilityNodeInfo? = null
            repeat(30) { if(field==null) { field=nodes().firstOrNull { it.isEditable };Thread.sleep(100) } }
            assertTrue(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "isolated drafted role")
            }))
            activity=SplitWindowAcceptance.recreate(activity,2f);mount()
            var restored=false
            repeat(30) { if(nodes().any { it.isEditable && it.text?.toString()=="isolated drafted role" })restored=true else Thread.sleep(100) }
            assertTrue("Unsaved role draft must survive actual recreation",restored)
        } finally { inst.runOnMainSync { activity.finish() } }
    }
}
