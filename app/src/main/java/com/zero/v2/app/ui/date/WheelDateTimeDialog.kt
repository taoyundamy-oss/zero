package com.zero.v2.app.ui.date

import android.os.Build
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale

internal enum class WheelDateMode { DATE, TIME, DATE_TIME }

/** Only mounted while open. Each native wheel owns a fixed-size visible selector, not a list of years. */
@Composable
internal fun WheelDateTimeDialog(title: String, initial: LocalDateTime, mode: WheelDateMode,
    dismiss: () -> Unit, select: (LocalDateTime) -> Unit, validate: (LocalDateTime) -> String? = { null }) {
    var dateDay by rememberSaveable { mutableLongStateOf(initial.toLocalDate().toEpochDay()) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var second by rememberSaveable { mutableIntStateOf(initial.second) }
    val date = LocalDate.ofEpochDay(dateDay)
    val candidate = date.atTime(hour, minute, second, initial.nano)
    val error = validate(candidate)
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (mode != WheelDateMode.TIME) DateWheels(date, minOf(1970, initial.year)..maxOf(9999, initial.year)) {
                dateDay = it.toEpochDay()
            }
            if (mode != WheelDateMode.DATE) Row(Modifier.fillMaxWidth()) {
                NumberWheel("时", hour, 0..23, Modifier.weight(1f)) { hour = it }
                NumberWheel("分", minute, 0..59, Modifier.weight(1f)) { minute = it }
                if (mode == WheelDateMode.DATE_TIME) NumberWheel("秒", second, 0..59, Modifier.weight(1f)) { second = it }
            }
            Text("滚动选择，点确定后应用；取消不改变原值。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { select(candidate) }, enabled = error == null) { Text("确定") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun DateWheels(date: LocalDate, years: IntRange, change: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        NumberWheel("年", date.year, years, Modifier.weight(1.25f)) { change(WheelDateValues.date(it, date.monthValue, date.dayOfMonth)) }
        NumberWheel("月", date.monthValue, 1..12, Modifier.weight(1f)) { change(WheelDateValues.date(date.year, it, date.dayOfMonth)) }
        NumberWheel("日", date.dayOfMonth, 1..YearMonth.from(date).lengthOfMonth(), Modifier.weight(1f)) {
            change(date.withDayOfMonth(it))
        }
    }
}

@Composable
private fun NumberWheel(label: String, value: Int, range: IntRange, modifier: Modifier, change: (Int) -> Unit) {
    val currentChange by rememberUpdatedState(change)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val nativeTheme = if (MaterialTheme.colorScheme.surface.luminance() < .5f)
        android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        AndroidView(modifier = Modifier.fillMaxWidth().height(128.dp), factory = { context ->
            NumberPicker(ContextThemeWrapper(context, nativeTheme)).apply {
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setFormatter { String.format(Locale.ROOT, "%02d", it) }
                contentDescription = label + "滚轮"
            }
        }, update = { picker ->
            picker.setOnValueChangedListener(null)
            picker.minValue = range.first
            picker.maxValue = range.last
            picker.value = value
            picker.wrapSelectorWheel = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) picker.textColor = textColor
            picker.setOnValueChangedListener { _, _, selected -> currentChange(selected) }
        })
    }
}
