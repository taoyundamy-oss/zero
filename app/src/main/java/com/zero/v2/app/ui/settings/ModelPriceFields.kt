package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zero.v2.services.llm.ModelTokenPrices

@Composable
internal fun ModelPriceFields(prices: ModelTokenPrices, onChange: (ModelTokenPrices) -> Unit) {
    Column {
        Text("模型价格 · 用户填写")
        Text("单位：美元（USD）/百万 tokens。按请求时价格累计，留空为未知、0为明确免费；修改不重算历史。")
        Text("套餐内调用（不计按量费；套餐固定费用及超额费需另行核对）")
        Switch(checked = prices.subscription, onCheckedChange = { onChange(prices.copy(subscription = it)) })
        if (!prices.subscription) {
            OutlinedTextField(prices.input, { onChange(prices.copy(input = it.trim())) },
                label = { Text("未命中输入价格") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prices.cached, { onChange(prices.copy(cached = it.trim())) },
                label = { Text("缓存命中输入价格") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prices.output, { onChange(prices.copy(output = it.trim())) },
                label = { Text("输出价格") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prices.cacheWrite, { onChange(prices.copy(cacheWrite = it.trim())) },
                label = { Text("独立缓存写入价格（适用时填写）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Text("仅支持统一 token 单价；阶梯价、多档缓存写入 TTL、按字符/音频时长计费应留空，不会自动换汇或拉取官网价格。")
    }
}
