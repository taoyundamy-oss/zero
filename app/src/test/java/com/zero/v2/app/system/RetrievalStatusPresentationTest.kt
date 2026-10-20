package com.zero.v2.app.system

import com.zero.v2.app.ui.settings.RetrievalStatusPresentation
import com.zero.v2.core.moduleapi.RetrievalDomainStatus
import com.zero.v2.core.moduleapi.StatusFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalStatusPresentationTest {
    @Test fun `ui retains unknown stale and incompatible distinctions from projection`() {
        val status = RetrievalDomainStatus("memory", "v2", false, "READY", 2, 10, 8, 1,
            StatusFact.STALE)
        assertTrue(RetrievalStatusPresentation.text(status).contains("状态已过期"))
        assertTrue(RetrievalStatusPresentation.text(status).contains("不兼容"))
        assertEquals("状态未知", RetrievalStatusPresentation.text(status.copy(freshness = StatusFact.UNKNOWN)))
        assertEquals("状态未知", RetrievalStatusPresentation.text(null))
        assertTrue(RetrievalStatusPresentation.text(status.copy(activeVersion = null, backlog = 0,
            freshness = StatusFact.LIVE)).contains("待索引 0"))
    }
}
