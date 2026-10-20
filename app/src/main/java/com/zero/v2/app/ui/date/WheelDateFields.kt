package com.zero.v2.app.ui.date

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun WheelDateTimeField(label: String, value: Long?, enabled: Boolean = true, change: (Long?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS") }
    Column {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label：${value?.let { WheelDateValues.local(it, zone).format(formatter) } ?: "未知／未指定"}")
        }
        if (value != null) TextButton(onClick = { change(null) }, enabled = enabled) { Text("清空$label") }
    }
    if (open) {
        val initial = remember(value) { value?.let { WheelDateValues.local(it, zone) } ?: LocalDateTime.now(zone).withNano(0) }
        WheelDateTimeDialog(label, initial, WheelDateMode.DATE_TIME, { open = false }, {
            change(WheelDateValues.epoch(it, zone, value)); open = false
        }, { candidate -> runCatching { WheelDateValues.epoch(candidate, zone, value) }.exceptionOrNull()?.message })
    }
}

@Composable
internal fun WheelDateField(label: String, value: LocalDate, change: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$value") }
    if (open) WheelDateTimeDialog(label, value.atStartOfDay(), WheelDateMode.DATE, { open = false }, {
        change(it.toLocalDate()); open = false
    })
}

@Composable
internal fun WheelTimeField(label: String, value: String, enabled: Boolean = true, change: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val time = runCatching { LocalTime.parse(value) }.getOrNull()
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("$label：${time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "未设置"}")
    }
    if (open) WheelDateTimeDialog(label, LocalDate.now().atTime(time ?: LocalTime.of(9, 0)), WheelDateMode.TIME,
        { open = false }, { change(it.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))); open = false })
}
