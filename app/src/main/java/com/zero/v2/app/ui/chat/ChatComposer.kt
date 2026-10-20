package com.zero.v2.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zero.v2.app.ui.GenerationState
import com.zero.v2.app.ui.theme.ZeroShapes

/** Full-width text above controls keeps the input readable on narrow screens and large fonts. */
@Composable
fun ChatComposer(text: String, onTextChange: (String) -> Unit, liveActive: Boolean, sendEnabled: Boolean,
    onAttach: () -> Unit, onOpenLive: () -> Unit, onSend: () -> Unit,
    isGenerating: Boolean = false, stopping: Boolean = false, onStop: () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = ZeroShapes.composer, color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column {
            TextField(text, onTextChange, Modifier.fillMaxWidth(), enabled = !liveActive,
                placeholder = { Text(if (liveActive) "Live 语音进行中" else "发送消息") }, maxLines = 4,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (sendEnabled) onSend() }))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onAttach, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Outlined.AttachFile, "添加图片、音频或视频（5MB以内）")
                }
                IconButton(onOpenLive, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Outlined.Mic, "打开 Live 语音")
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(if (isGenerating) onStop else onSend, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    enabled = if (isGenerating) !stopping else sendEnabled) {
                    if (isGenerating) Icon(Icons.Outlined.Stop, if (stopping) "正在停止" else "停止回复")
                    else Icon(Icons.AutoMirrored.Outlined.Send, "发送")
                }
            }
        }
    }
}

/** Error is a terminal visible state, never an indeterminate loading animation. */
@Composable
fun ChatGenerationStatus(state: GenerationState, onRetry: (() -> Unit)? = null, retryEnabled: Boolean = true) {
    if (state == GenerationState.IDLE) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state == GenerationState.GENERATING || state == GenerationState.CALLING_TOOL) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(when (state) {
            GenerationState.CALLING_TOOL -> "正在使用工具"
            GenerationState.STOPPING -> "正在停止回复"
            GenerationState.STOPPED -> "已停止回复"
            GenerationState.ERROR -> "本轮未完成，请查看运行记录或调整后重试"
            else -> "正在回复"
        }, style = MaterialTheme.typography.bodyMedium,
            color = if (state == GenerationState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (state == GenerationState.ERROR && onRetry != null) {
            Text("重新生成会再次调用模型和工具；已完成的工具操作不会撤销。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRetry, enabled = retryEnabled) { Text("重新生成本轮") }
        }
    }
}
