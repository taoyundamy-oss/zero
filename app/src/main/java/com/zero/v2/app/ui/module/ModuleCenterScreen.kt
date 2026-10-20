package com.zero.v2.app.ui.module

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import com.zero.v2.app.ui.theme.ZeroCard
import com.zero.v2.app.ui.theme.ZeroPageHeader
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zero.v2.core.moduleapi.runtimeState

/**
 * 模块管理中心 — 点击卡片进入 ModuleDetailScreen。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleCenterScreen(onBack: () -> Unit, onOpenSettings: (String) -> Unit,
    viewModel: ModuleCenterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var selectedModuleId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(selectedModuleId != null) { selectedModuleId = null }

    if (selectedModuleId != null) {
        ModuleDetailScreen(
            moduleId = selectedModuleId!!,
            onBack = { selectedModuleId = null }, onOpenSettings = onOpenSettings
        )
    } else {
        Scaffold(
            topBar = { ZeroPageHeader("能力与模型", onBack = onBack) }
        ) { padding ->
            if (state.modules.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("暂无已注册模块", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.zero.v2.core.moduleapi.ModuleCategory.entries.forEach { category ->
                        val catModules = state.modules.filter { it.metadata.category == category }
                        if (catModules.isNotEmpty()) {
                            item {
                                Text("${category.label} (${catModules.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                            }
                            items(catModules, key = { it.metadata.id }) { module ->
                                ZeroCard(Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        // 点击信息区 → 进入详情页
                                        Column(
                                            modifier = Modifier.weight(1f)
                                                .clickable { selectedModuleId = module.metadata.id }
                                        ) {
                                            Text(module.metadata.displayName,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Medium)
                                            Text(module.metadata.description.take(60),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("ID: ${module.metadata.id} · v${module.metadata.version}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        // 启用/禁用开关
                                        Switch(
                                            checked = state.runtimeStates[module.metadata.id]?.operational
                                                ?: module.runtimeState().operational,
                                            onCheckedChange = { viewModel.toggleModule(module.metadata.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
