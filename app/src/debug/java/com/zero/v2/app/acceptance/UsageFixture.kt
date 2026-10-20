package com.zero.v2.app.acceptance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zero.v2.app.ui.settings.*
import com.zero.v2.core.data.dao.UsageBucket
import com.zero.v2.core.data.entity.UsageRequestEntity
import java.time.LocalDate

/** Isolated UI data; never inserted into the user's ledger. */
@Composable
internal fun UsageFixture() {
    val day = LocalDate.of(2026, 10, 13)
    val window = UsageWindow(day, day)
    val buckets = (0..23).map { hour ->
        val input = (hour % 5 + 1) * 100000L
        UsageBucket(window.start + hour * 3600000L, 10, input, input * 9 / 10,
            input, 10000, 10, 0, 0, 0, 0)
    }
    val events = (0..219).map { index -> UsageRequestEntity("fixture$index", window.start + index * 60000L,
        connectionId = "隔离连接", profileId = "fixture", model = "隔离模型", kind = "stream",
        status = if (index == 3) "failed" else "completed", inputTokens = 1000,
        cachedTokens = if (index == 3) null else if (index == 5) 0 else 980,
        outputTokens = 50, continuity = if (index == 5) "generation_switch/first_request" else "append_only") }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("成本与用量 · 隔离图表验收")
        UsageOverview(UsageReport.total(buckets))
        UsageTimeChart(buckets, window)
        UsageEventChart(events, window)
    }
}
