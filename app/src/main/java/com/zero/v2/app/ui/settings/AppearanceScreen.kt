package com.zero.v2.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.app.ui.theme.ZeroCard

@Composable
fun AppearanceScreen(onBack: () -> Unit, viewModel: AppearanceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    AppearanceContent(state, onBack, viewModel::setMode, viewModel::setDynamic)
}

@Composable
internal fun AppearanceContent(state: AppearanceState, onBack: () -> Unit, onMode: (AppearanceMode) -> Unit, onDynamic: (Boolean) -> Unit) {
    Scaffold(topBar = { com.zero.v2.app.ui.theme.ZeroPageHeader("外观", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("明暗模式", style = MaterialTheme.typography.titleMedium)
            ZeroCard(Modifier.fillMaxWidth()) {
                Column(Modifier.selectableGroup()) {
                    AppearanceMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(state.mode == mode, role = Role.RadioButton, onClick = { onMode(mode) })
                            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(state.mode == mode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Text("配色", style = MaterialTheme.typography.titleMedium)
            ZeroCard(Modifier.fillMaxWidth()) {
                com.zero.v2.app.ui.theme.ZeroSettingSwitch(
                    "系统动态色", "使用壁纸配色；关闭后使用 Zero 中性色。旧版系统使用 Zero 配色。",
                    state.dynamicColor, onDynamic)
            }

            Text("字体大小跟随系统设置，界面会随字号调整布局。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
