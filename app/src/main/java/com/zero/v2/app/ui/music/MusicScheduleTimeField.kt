package com.zero.v2.app.ui.music

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.zero.v2.app.ui.date.WheelDateTimeField
import com.zero.v2.app.ui.date.WheelDateValues
import com.zero.v2.app.ui.date.WheelTimeField
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Calendar/clock input is wheel-only; relative delays keep the existing scheduling contract. */
@Composable
internal fun MusicScheduleTimeField(value: String, change: (String) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("DATE") }
    var instant by rememberSaveable { mutableStateOf<Long?>(null) }
    var delay by rememberSaveable { mutableStateOf("") }
    Column {
        listOf("DATE" to "指定日期时间", "CLOCK" to "下一次时刻", "DELAY" to "相对延迟").forEach { (id, label) ->
            FilterChip(mode == id, {
                if (mode != id) { mode = id; change(""); instant = null; delay = "" }
            }, label = { Text(label) })
        }
        when (mode) {
            "DATE" -> WheelDateTimeField("播放时间", instant) {
                instant = it
                change(it?.let { at -> WheelDateValues.local(at, ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) }.orEmpty())
            }
            "CLOCK" -> WheelTimeField("播放时刻", value) { change(it) }
            else -> OutlinedTextField(delay, {
                delay = it
                change(it.trim().takeIf { text -> text.matches(Regex("\\+\\d+[smhd]")) }.orEmpty())
            }, label = { Text("相对延迟") }, supportingText = { Text("例如 +90s、+5m；具体日期或时刻请用滚轮选择。") },
                singleLine = true, isError = delay.isNotBlank() && value.isBlank())
        }
    }
}
