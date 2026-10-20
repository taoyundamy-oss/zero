package com.zero.v2.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** One runner test boundary: do not clear Samsung's split task between the six existing contracts. */
class SharedWindowRecoveryAcceptanceTest {
    @Test fun currentOwnersKeepOneActualSplitAcrossAllSixContracts() {
        val rows = JSONArray()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "a11-shared-window.json")
        val input = AutomationInputAcceptanceTest()
        val contracts = listOf<Pair<String, () -> Unit>>(
            "keyboard-denied-generation" to { input.descriptionKeyboardAndDeniedGenerationRetainInputAcrossRecreation() },
            "editor-rejected-save" to { input.largeEditorOptionsAndRejectedIntervalKeepDraft() },
            "list-tail-and-reading-position" to { input.lastLongTaskClearsFabAndListPositionSurvivesRecreation() },
            "list-editor-requirement-eight-themes" to { input.listEditorAndRequirementFollowAllEightThemes() },
            "confirmed-create-eight-themes" to { AutomationCreateNarrowAcceptanceTest().executionAndScheduleChoicesFitLargeSplit() },
            "root-appearance-eight-themes" to { SettingsRootAppearanceAcceptanceTest().rootEntriesTailAndActualAppearancePersistenceInEightConditions() },
        )
        fun checkpoint(complete: Boolean, phase: String) {
            file.writeText(JSONObject().put("complete", complete).put("phase", phase).put("contracts", rows)
                .put("providerRequests", 0).put("personalDataWrites", 0)
                .put("scope", "six existing actual multi-window contracts inside one runner boundary").toString(2))
        }
        checkpoint(false, "await-first-contract")
        for ((name, run) in contracts) {
            checkpoint(false, name)
            val started = android.os.SystemClock.elapsedRealtime()
            run()
            rows.put(JSONObject().put("name", name).put("passed", true)
                .put("durationMs", android.os.SystemClock.elapsedRealtime() - started))
            checkpoint(false, "completed:$name")
        }
        checkpoint(true, "complete")
    }
}
