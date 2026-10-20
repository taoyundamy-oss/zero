package com.zero.v2.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.zero.v2.core.common.CharacterStateReducer
import kotlinx.coroutines.delay

@Composable
internal fun CharacterStateExplanation(state: CharacterStateUiState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    val cooldown = (state.lastAffinityChangeAt + CharacterStateReducer().affinityCooldownMs - now).coerceAtLeast(0)
    val mood = if (state.moodExpiresAt > now) state.moodLabel else "平静"
    val status = if (!state.loaded || state.error != null) "状态尚未确认" else
        "最近有记录会话的情绪：$mood；" + if (cooldown > 0) "升阶冷却剩余约 ${(cooldown + 59_999) / 60_000} 分钟" else "升阶冷却已结束"
    Text(status, style = MaterialTheme.typography.bodySmall)
    Text("亲密度：初识 → 熟络 → 亲近 → 深契。明确的正向关系表达每次最多升一档，默认间隔6小时；聊天次数不等于升级进度。",
        style = MaterialTheme.typography.bodySmall)
    Text("情绪：平静、温和、关切、欢快。短期状态默认30分钟后回到平静；负面反馈不降低亲密度。关键词判断有局限，可查看变化依据或重置。",
        style = MaterialTheme.typography.bodySmall)
}
