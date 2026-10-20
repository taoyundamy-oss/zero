package com.zero.v2.app.ui.settings

import com.zero.v2.core.data.dao.UsageBucket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class UsageWindow(val first: LocalDate, val last: LocalDate, val zone: ZoneId = ZoneId.systemDefault()) {
    init { require(!last.isBefore(first) && last.toEpochDay() - first.toEpochDay() < 366) }
    val start: Long get() = first.atStartOfDay(zone).toInstant().toEpochMilli()
    val end: Long get() = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

object UsageReport {
    fun total(rows: List<UsageBucket>, bucket: Long = 0) = UsageBucket(bucket,
        rows.sumOf { it.requests }, rows.sumOf { it.input }, rows.sumOf { it.cached },
        rows.sumOf { it.observedInput }, rows.sumOf { it.output }, rows.sumOf { it.knownCache },
        rows.sumOf { it.unknownInput }, rows.sumOf { it.unfinished }, rows.sumOf { it.failed },
        rows.sumOf { it.localRequests })

    fun chart(rows: List<UsageBucket>, window: UsageWindow): List<UsageBucket> {
        if (window.first == window.last) {
            val byTime = rows.associateBy { it.bucket }
            return generateSequence(window.start) { it + 3_600_000 }.takeWhile { it < window.end }
                .map { byTime[it] ?: total(emptyList(), it) }.toList()
        }
        val byDay = rows.groupBy { Instant.ofEpochMilli(it.bucket).atZone(window.zone).toLocalDate() }
        return generateSequence(window.first) { it.plusDays(1) }.takeWhile { !it.isAfter(window.last) }
            .map { total(byDay[it].orEmpty(), it.atStartOfDay(window.zone).toInstant().toEpochMilli()) }.toList()
    }

    fun percent(numerator: Long, denominator: Long): String = if (denominator <= 0) "未知"
        else String.format(java.util.Locale.ROOT, "%.2f%%", numerator.toDouble() / denominator * 100)
}
