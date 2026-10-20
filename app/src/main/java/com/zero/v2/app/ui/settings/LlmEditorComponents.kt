package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zero.v2.services.llm.ModelCapability

@Composable
internal fun LlmSectionHeading(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
            Text(number, Modifier.padding(10.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LlmProtocolSummary(contractId: String, modelId: String) {
    val responses = contractId in setOf("cf-luna-responses-v1", "cf-openai-responses-v1")
    val native = contractId == "cf-anthropic-native-v1"
    val title = when { responses -> "Responses"; native -> "Anthropic Messages"; else -> "Chat Completions" }
    val hint = when {
        responses -> "协议可承载思考、图片与工具；实际能力和参数请按模型选择。"
        native -> "原生思考与签名续接；请设置思考预算。"
        modelId == "openai/gpt-6-luna" -> "Luna使用工具时思考强度须为none；思考与工具并用请选择Responses契约。"
        else -> "按模型支持选择参数；留空使用服务端默认。"
    }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(hint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LlmCapabilityChoices(selected: List<String>, onChange: (List<String>) -> Unit) {
    Text("能力用于自动选模。思考开关由上方参数控制，勾选能力不会开启思考。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModelCapability.entries.forEach { capability ->
            FilterChip(selected = capability.name in selected, onClick = {
                onChange((if (capability.name in selected) selected - capability.name else selected + capability.name).sorted())
            }, label = { Text(capabilityLabel(capability)) })
        }
    }
}

private fun capabilityLabel(capability: ModelCapability): String = when (capability) {
    ModelCapability.CHAT -> "文本对话"
    ModelCapability.REASONING -> "推理思考"
    ModelCapability.VISION -> "视觉理解"
    ModelCapability.IMAGE_INPUT -> "图片输入"
    ModelCapability.AUDIO_INPUT -> "音频输入"
    ModelCapability.VIDEO_INPUT -> "视频输入"
    ModelCapability.FUNCTION_CALLING -> "工具调用"
    ModelCapability.LONG_CONTEXT -> "长上下文"
    ModelCapability.FAST_RESPONSE -> "快速响应"
}
