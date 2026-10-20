package com.zero.v2.app.ui.date

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Calendar and zone rules shared by the wheels; no strings or database writes. */
internal object WheelDateValues {
    fun date(year: Int, month: Int, day: Int): LocalDate =
        LocalDate.of(year, month, day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth()))

    fun local(epoch: Long, zone: ZoneId): LocalDateTime = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDateTime()

    fun epoch(value: LocalDateTime, zone: ZoneId, previous: Long? = null): Long {
        val offsets = zone.rules.getValidOffsets(value)
        require(offsets.isNotEmpty()) { "所选时间处于夏令时跳过时段，请选择其他时间" }
        val preferred = previous?.let { Instant.ofEpochMilli(it).atZone(zone).offset }
        val offset = preferred?.takeIf { it in offsets } ?: offsets.first()
        return value.toInstant(offset).toEpochMilli().also { require(it >= 0) { "时间不能早于1970年时间戳起点" } }
    }
}
