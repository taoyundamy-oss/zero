package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zero.v2.services.llm.ModelContracts
import com.zero.v2.services.llm.ParameterType
import org.json.JSONObject

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ContractParameterFields(
    contractId: String,
    parameterJson: String,
    onContract: (String) -> Unit,
    onParameters: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val contract = ModelContracts.all.find { it.id == contractId }
    val values = JSONObject(parameterJson)
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("${contract?.label ?: contractId}  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ModelContracts.all.forEach { item ->
                DropdownMenuItem(text = { Text(item.label) }, onClick = {
                    onContract(item.id)
                    expanded = false
                })
            }
        }
    }
    Text("按模型文档选择契约；留空使用服务默认。切换契约保留草稿，需清除不支持的参数。",
        style = MaterialTheme.typography.bodySmall)
    contract?.parameters?.forEach { spec ->
        fun change(raw: String) {
            if (raw.isBlank()) values.remove(spec.key) else values.put(spec.key, raw.trim())
            onParameters(values.toString())
        }
        Text(spec.label, style = MaterialTheme.typography.labelLarge)
        if (spec.type == ParameterType.INTEGER) {
            OutlinedTextField(value = values.optString(spec.key, ""), onValueChange = ::change,
                label = { Text(spec.label) }, supportingText = { Text("${spec.minimum}–${spec.maximum}；留空不发送") },
                singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
        } else {
            val options = if (spec.type == ParameterType.BOOLEAN) listOf("true", "false") else spec.values
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("") + options).forEach { option ->
                    val label = when (option) { "" -> "服务默认"; "true" -> "开启"; "false" -> "关闭"; else -> option }
                    FilterChip(selected = values.optString(spec.key, "") == option,
                        onClick = { change(option) }, label = { Text(label) })
                }
            }
            if (values.has(spec.key) && values.getString(spec.key) !in options) {
                Text("当前值不受支持：${values.getString(spec.key)}，请选择有效值。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val unknown = values.keys().asSequence().filter { key -> contract?.parameters?.none { it.key == key } != false }.toList()
    unknown.forEach { key ->
        TextButton(onClick = { values.remove(key); onParameters(values.toString()) }) {
            Text("移除不支持的参数：$key", color = MaterialTheme.colorScheme.error)
        }
    }
}
