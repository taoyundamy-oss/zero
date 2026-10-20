package com.zero.v2.services.retrieval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentRecallPolicyTest {
    @Test fun englishCurrentQueriesExcludeRetiredChineseSnapshots() {
        for (query in listOf("Where is training now?", "What is the latest corrected schedule?",
            "Which schedule is currently in effect?", "What is the CURRENT office location?")) {
            assertFalse(query, CurrentRecallPolicy.eligible(query, old))
        }
    }

    @Test fun englishHistoricalQueriesRetainEvidenceAcrossLanguages() {
        for (query in listOf("Compare the current and previous schedules", "What changed since the old schedule?",
            "What was the location before the correction and where is it now?",
            "Show the history of the latest schedule")) {
            assertTrue(query, CurrentRecallPolicy.eligible(query, old))
        }
    }

    @Test fun englishCurrentWordsAreNotSubstringTriggers() {
        for (query in listOf("Explain currency", "What is known about this arrangement?", "Describe the snow scene")) {
            assertTrue(query, CurrentRecallPolicy.eligible(query, old))
        }
    }

    private val old = "旧培训安排：培训地点是三楼302室，后续已被更正。"
    @Test fun currentRequestExcludesExplicitlyRetiredSnapshot() {
        assertFalse(CurrentRecallPolicy.eligible("更正后培训地点", old))
        assertFalse(CurrentRecallPolicy.eligible("目前办公地点", "之前临时办公地点是南楼102室，已结束使用。"))
        assertFalse(CurrentRecallPolicy.eligible("本周生效值班安排", "上周值班人员为陈远，旧值班表已结束。"))
    }
    @Test fun historicalAndComparisonQueriesKeepOldSnapshot() {
        for (query in listOf("旧培训安排", "更正前后对比", "目前与历史安排有何变化", "培训地点")) {
            assertTrue(CurrentRecallPolicy.eligible(query, old))
        }
    }
    @Test fun currentCorrectionAndUncertainOldContentAreNotDiscarded() {
        assertTrue(CurrentRecallPolicy.eligible("更正后培训地点", "培训地点更正为五楼501室，原三楼302室安排失效。"))
        assertTrue(CurrentRecallPolicy.eligible("目前收藏有哪些", "旧手机是我的收藏品。"))
        assertTrue(CurrentRecallPolicy.eligible("最新运行情况", "当前系统中有已失效的令牌，需要修复。"))
        assertTrue(CurrentRecallPolicy.eligible("现在体重", "之前体重120斤，现已调整为110斤。"))
        assertTrue(CurrentRecallPolicy.eligible("目前材料用量", "原材料用量已调整。"))
        assertTrue(CurrentRecallPolicy.eligible("目前材料状态", "原材料已失效，需要更换。"))
        assertTrue(CurrentRecallPolicy.eligible("最新设备状态", "旧设备电池已失效，需要维修。"))
    }
}
