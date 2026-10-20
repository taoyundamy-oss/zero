package com.zero.v2.services.retrieval

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentRecallWindowTest {
    @Test fun datedSourcesCanReachBoundedHeadWithoutDroppingSemanticCandidates() {
        val rows = (0 until 100).toList()
        val today = LocalDate.now().toEpochDay()
        val arranged = RecentRecallWindow.arrange("我们最近做了什么", rows, { "$it" }, {
            if (it >= 90) today - (100 - it) else null
        })
        assertEquals(listOf(99, 98, 97, 96, 0, 1, 2, 3), arranged.take(8))
        assertEquals(rows.toSet(), arranged.toSet())
        assertEquals(rows, RecentRecallWindow.arrange("最近数据库如何优化", rows, { "$it" }, { today }))
        assertEquals(rows, RecentRecallWindow.arrange("我最近在做什么", rows, { "$it" }, { today + 1 }))
    }

    @Test fun onlyExplicitValidDatesSupplyRecencyNotIndexOrAccessTimestamps() {
        assertNull(RecentRecallWindow.date("今日重建：2026-02-30"))
        assertEquals(LocalDate.of(2026, 10, 3).toEpochDay(), RecentRecallWindow.date("见闻 > 2026-10-03 · 旅行"))
        assertEquals(100, RecentRecallWindow.limit("我最近在做什么", 8))
        assertEquals(8, RecentRecallWindow.limit("按更正通知，培训在哪里", 8))
    }
}
