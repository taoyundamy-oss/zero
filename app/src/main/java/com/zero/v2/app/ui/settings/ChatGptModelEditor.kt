package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.zero.v2.services.llm.LlmProfile
import com.zero.v2.services.llm.ModelCapability
import com.zero.v2.services.llm.ModelContracts

/** Subscription credentials and destination cannot be edited as an API connection. */
@Composable
internal fun ChatGptModelEditor(profile: LlmProfile, onSave: (LlmProfile) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.displayName) }
    var context by rememberSaveable(profile.id) { mutableStateOf(profile.contextWindow.toString()) }
    var reserve by rememberSaveable(profile.id) { mutableStateOf(profile.maxTokens.toString()) }
    var priority by rememberSaveable(profile.id) { mutableStateOf(profile.priority.toString()) }
    var effort by rememberSaveable(profile.id) { mutableStateOf(profile.parameters["effort"].orEmpty()) }
    var tools by rememberSaveable(profile.id) { mutableStateOf(ModelCapability.FUNCTION_CALLING in profile.capabilities) }
    var images by rememberSaveable(profile.id) { mutableStateOf(ModelCapability.IMAGE_INPUT in profile.capabilities) }
    var error by rememberSaveable(profile.id) { mutableStateOf("") }
    Column {
        Text("ChatGPT套餐 · ${profile.modelId}")
        Text("账号连接固定。套餐用量单列；不按API单价估算。目录缺失的能力请按该模型实际支持情况填写。")
        OutlinedTextField(name, { name = it }, label = { Text("显示名称") })
        OutlinedTextField(context, { context = it }, label = { Text("上下文容量（tokens）") })
        OutlinedTextField(reserve, { reserve = it }, label = { Text("本地输出预留（非服务端回复上限）") })
        Text("套餐接口不支持输出上限参数；此值只用于上下文规划。不会降低思考强度。")
        OutlinedTextField(priority, { priority = it }, label = { Text("选择优先级") })
        OutlinedTextField(effort, { effort = it }, label = { Text("思考强度（留空使用模型默认）") })
        TextButton(onClick = { tools = !tools }) { Text("函数工具：${if (tools) "启用" else "未声明"}") }
        TextButton(onClick = { images = !images }) { Text("图片输入：${if (images) "启用" else "未声明"}") }
        Text(error)
        Button(onClick = {
            runCatching {
                val capabilities = profile.capabilities.toMutableSet().apply {
                    removeAll(setOf(ModelCapability.FUNCTION_CALLING, ModelCapability.IMAGE_INPUT, ModelCapability.VISION))
                    if (tools) add(ModelCapability.FUNCTION_CALLING)
                    if (images) addAll(setOf(ModelCapability.IMAGE_INPUT, ModelCapability.VISION))
                }
                profile.copy(displayName = name, contextWindow = context.toInt(), maxTokens = reserve.toInt(),
                    priority = priority.toInt(), capabilities = capabilities,
                    parameters = if (effort.isBlank()) emptyMap() else mapOf("effort" to effort.trim()))
                    .also { ModelContracts.get(it.contractId).validate(it) }
            }.onSuccess(onSave).onFailure { error = "请核对整数范围和思考强度：${it.message.orEmpty()}" }
        }) { Text("保存") }
        TextButton(onClick = onBack) { Text("返回（放弃未保存修改）") }
    }
}
